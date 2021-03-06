#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    parameters([
        booleanParam(name: 'USE_CACHE',
                     defaultValue: false,
                     description: 'Enable use of any binary packages cached locally from previous builds.'),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'MANIFEST_REF',
               defaultValue: 'refs/tags/'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'TriggeredBuildSelector',
                         allowUpstreamDependencies: true,
                         fallbackToLastSuccessful: true,
                         upstreamFilterStrategy: 'UseGlobalSetting']])

        withCredentials([
            [$class: 'FileBinding',
             credentialsId: 'buildbot-official.2E16137F.subkey.gpg',
             variable: 'GPG_SECRET_KEY_FILE'],
            [$class: 'FileBinding',
             credentialsId: 'jenkins-coreos-systems-write-5df31bf86df3.json',
             variable: 'GOOGLE_APPLICATION_CREDENTIALS']
        ]) {
            withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                     "MANIFEST_NAME=${params.MANIFEST_NAME}",
                     "MANIFEST_REF=${params.MANIFEST_REF}",
                     "MANIFEST_URL=${params.MANIFEST_URL}",
                     'USE_CACHE=' + (params.USE_CACHE ? 'true' : 'false')]) {
                sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_REF#refs/tags/}" ]]

# hack because catalyst leaves things chowned as root
[[ -d .cache/sdks ]] && sudo chown -R $USER .cache/sdks

./bin/cork update --create --downgrade-replace --verify --verbose \
                                    --manifest-url "${MANIFEST_URL}" \
                                    --manifest-branch "${MANIFEST_REF}" \
                                    --manifest-name "${MANIFEST_NAME}"

enter() {
    ./bin/cork enter --experimental -- "$@"
}

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
export GNUPGHOME="${PWD}/.gnupg"
sudo rm -rf "${GNUPGHOME}"
trap "sudo rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import "${GPG_SECRET_KEY_FILE}"

# Wipe all of catalyst or just clear out old tarballs taking up space
sudo rm -rf src/build/catalyst/builds
if [[ "${COREOS_OFFICIAL:-0}" -eq 1 || "$USE_CACHE" == false ]]; then
    sudo rm -rf src/build
fi

S=/mnt/host/source/src/scripts
enter ${S}/update_chroot
enter sudo emerge -uv --jobs=2 catalyst
enter sudo ${S}/bootstrap_sdk \
    --sign buildbot@coreos.com --sign_digests buildbot@coreos.com \
    --upload --upload_root gs://builds.developer.core-os.net

# Free some disk space only on success, for debugging failures
sudo rm -rf src/build/catalyst/builds
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        fingerprint 'src/build/catalyst/packages/coreos-sdk/**/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}
