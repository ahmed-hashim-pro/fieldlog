# JDK 17 + the Android SDK pieces the app needs, so the APK builds with
# nothing installed on the host but Docker.
FROM eclipse-temurin:17-jdk
ENV ANDROID_HOME=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL --retry 5 --retry-all-errors -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip
# Google's SDK servers fail now and then; try the install a few times.
RUN yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
 && for i in 1 2 3; do \
      $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "platforms;android-35" "build-tools;34.0.0" "platform-tools" > /tmp/sdk.log 2>&1 && break; \
      echo "sdkmanager attempt $i failed:"; tail -5 /tmp/sdk.log; sleep 10; \
    done \
 && test -d $ANDROID_HOME/platforms/android-35 && test -d $ANDROID_HOME/build-tools/34.0.0 \
 && chmod -R a+rwX $ANDROID_HOME
