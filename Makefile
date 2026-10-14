# Everything runs in Docker; nothing but Docker is needed on the host.
# (`make phones` is the exception: it needs an Android SDK with the emulator, and KVM.)
U := $(shell id -u):$(shell id -g)
CACHE := $(CURDIR)/.gradle-home
JDK := docker run --rm -u $(U) -e HOME=/cache/home -e GRADLE_USER_HOME=/cache -v $(CACHE):/cache -v $(CURDIR):/w -w /w eclipse-temurin:17-jdk
ANDROID := docker run --rm -u $(U) -e HOME=/cache/home -e GRADLE_USER_HOME=/cache -e ANDROID_USER_HOME=/cache/android \
	-v $(CACHE):/cache -v $(CURDIR):/w -w /w fieldlog-android:dev

.PHONY: test cli fuzz apk android-image strata-check check demo phones clean

$(CACHE):
	mkdir -p $(CACHE)/home $(CACHE)/android

## core and CLI tests on the JVM (the CRDT, fuzzer, strata export, relay)
test: | $(CACHE)
	$(JDK) ./gradlew -q -Pjvm-only :core:test :cli:test

## build/install/fieldlog/bin/fieldlog
cli: | $(CACHE)
	$(JDK) ./gradlew -q -Pjvm-only :cli:installDist

## the long fuzz runs: 20,000 seeds of the correct merge, and every mutant
fuzz: cli
	$(JDK) cli/build/install/fieldlog/bin/fieldlog fuzz hunt --seeds 20000
	$(JDK) cli/build/install/fieldlog/bin/fieldlog fuzz mutants --seeds 2000

android-image:
	docker build -q -f docker/android.Dockerfile -t fieldlog-android:dev docker

## app/build/outputs/apk/debug/app-debug.apk, plus the app's unit tests
apk: android-image | $(CACHE)
	$(ANDROID) ./gradlew -q :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest

## the export, read by the real strata: validate it, and check import->export changes nothing strata sees
strata-check: cli
	./scripts/strata-check.sh

check: test fuzz strata-check apk

## docs/demo.gif (terminal)
demo: cli
	docker build -q -f docs/demo.Dockerfile -t fieldlog-demo:dev docs
	docker run --rm -u $(U) -e HOME=/tmp -v $(CURDIR):/vhs -w /vhs fieldlog-demo:dev docs/demo.tape

## docs/two-phones.{mp4,gif}: two emulators and a relay (needs an Android SDK and KVM)
phones: apk cli
	./scripts/two-phones.sh

clean:
	rm -rf build */build .gradle .kotlin
