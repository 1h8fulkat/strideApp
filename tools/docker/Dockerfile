# The build and deploy container for STRIDE.
#
# Everything the console APK needs — JDK 17, the Android SDK, and adb — in one
# image, so a machine that builds STRIDE needs nothing installed on it but
# Docker. The repo's own console/stride/run.sh assumes a developer laptop with
# a JDK and an SDK already set up; this is the other route.
#
# Build it with tools/docker-build.sh, which does this for you, or by hand:
#
#     docker build -t stride-build:latest tools/docker
#
# Pinned on purpose. The SDK packages come from app/build.gradle.kts —
# compileSdk 34 wants platform 34, and build-tools follows it — and the
# command-line tools zip is pinned to a build number rather than "latest" so
# that rebuilding this image a year from now produces the same toolchain
# rather than whatever Google is shipping that week.
FROM eclipse-temurin:17-jdk

# 11076708 is command-line tools 11.0. Bump deliberately, not incidentally.
ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
ARG ANDROID_PLATFORM=platforms;android-34
ARG ANDROID_BUILD_TOOLS=build-tools;34.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl unzip; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "$ANDROID_HOME/cmdline-tools"; \
    cd /tmp; \
    curl -sSLo cmdline.zip "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"; \
    unzip -q cmdline.zip; \
    mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"; \
    rm cmdline.zip; \
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null; \
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" > /dev/null; \
    # build-tools is not on PATH and its directory carries the version in its
    # name, so the tools that live there are linked out to a stable place. It
    # is apksigner that matters: every build prints the signing certificate,
    # because that is the number deciding whether the next install replaces
    # what is on the console or is refused by it.
    ln -s "$ANDROID_HOME"/build-tools/*/apksigner /usr/local/bin/apksigner; \
    ln -s "$ANDROID_HOME"/build-tools/*/aapt2     /usr/local/bin/aapt2; \
    ln -s "$ANDROID_HOME"/build-tools/*/zipalign  /usr/local/bin/zipalign; \
    # The container runs as the *host's* uid so that files it writes into the
    # mounted repo belong to you rather than to root. That uid owns nothing in
    # here, so everything it needs has to be world-readable.
    chmod -R a+rX "$ANDROID_HOME"

ENV PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

WORKDIR /src
