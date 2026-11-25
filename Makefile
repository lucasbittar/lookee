# lookee build pipeline: Go tests -> gomobile bind -> Gradle APK -> adb install.
# Toolchain paths default to the standard macOS locations; override on the command line,
# e.g. `make bind ANDROID_HOME=/opt/android-sdk`.

ANDROID_HOME     ?= $(HOME)/Library/Android/sdk
NDK_VERSION      ?= 28.2.13676358
ANDROID_NDK_HOME ?= $(ANDROID_HOME)/ndk/$(NDK_VERSION)
JAVA_HOME        ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null)
GOBIN            ?= $(shell go env GOPATH)/bin
# Built from source with the local Go toolchain, so it always understands the Go version in use.
GOLANGCI_LINT    ?= go run github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.15.0

export ANDROID_HOME ANDROID_NDK_HOME JAVA_HOME
export PATH := $(GOBIN):$(ANDROID_HOME)/platform-tools:$(PATH)

# Full paths: GNU Make 3.81 (macOS default) ignores an exported PATH when it runs simple commands.
ADB      ?= $(ANDROID_HOME)/platform-tools/adb
GOMOBILE ?= $(GOBIN)/gomobile

AAR          := app/libs/lookeecore.aar
APK          := app/build/outputs/apk/debug/app-debug.apk
# Release targets the Mi Box (32-bit and 64-bit ARM); debug adds x86 for the Android 9 TV emulator.
TARGETS      ?= android/arm,android/arm64
DEBUG_TARGETS ?= $(TARGETS),android/386
MIBOX_IP     ?=

.PHONY: all test lint cli bind bind-release apk install devices clean doctor

all: test bind apk

## test: run Go unit tests
test:
	cd core && go test ./...

## lint: vet and golangci-lint the Go core
lint:
	cd core && go vet ./... && $(GOLANGCI_LINT) run ./...

## cli: build the local harness to core/lookee-cli
cli:
	cd core && go build -o lookee-cli ./cmd/lookee-cli

## bind: build the Android library (debug: includes x86 for the emulator)
bind:
	@mkdir -p app/libs
	cd core && $(GOMOBILE) bind -target=$(DEBUG_TARGETS) -androidapi 26 -javapkg com.lookee.core -o ../$(AAR) .

## bind-release: ARM-only Android library for release builds
bind-release:
	@mkdir -p app/libs
	cd core && $(GOMOBILE) bind -target=$(TARGETS) -androidapi 26 -javapkg com.lookee.core -trimpath -ldflags="-s -w" -o ../$(AAR) .

## apk: build the debug APK (Phase 3+)
apk: $(AAR)
	./gradlew assembleDebug

$(AAR):
	$(MAKE) bind

## install: install the debug APK on the Mi Box (make install MIBOX_IP=192.168.x.x)
install:
ifneq ($(MIBOX_IP),)
	$(ADB) connect $(MIBOX_IP):5555
endif
	$(ADB) install -r $(APK)

## devices: show the Mi Box's model, Android version and ABIs
devices:
ifneq ($(MIBOX_IP),)
	$(ADB) connect $(MIBOX_IP):5555
endif
	@$(ADB) shell 'echo "model:   $$(getprop ro.product.model)"; echo "android: $$(getprop ro.build.version.release) (API $$(getprop ro.build.version.sdk))"; echo "abis:    $$(getprop ro.product.cpu.abilist)"'

## doctor: check the toolchain
doctor:
	@go version
	@java -version 2>&1 | head -1
	@test -x "$(GOBIN)/gomobile" && echo "gomobile: $(GOBIN)/gomobile" || echo "gomobile: MISSING (go install golang.org/x/mobile/cmd/gomobile@latest)"
	@test -d "$(ANDROID_NDK_HOME)" && echo "ndk:      $(ANDROID_NDK_HOME)" || echo "ndk:      MISSING ($(ANDROID_NDK_HOME))"
	@$(ADB) version | head -1

clean:
	rm -rf app/libs/*.aar app/libs/*.jar core/lookee-cli
