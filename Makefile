.DEFAULT_GOAL := help
export REASON SERIAL BASE
.PHONY: help setup android-setup browser-setup build android-build test android-test ui-generate ui-check ui-visual ui-accept check-public simulator install
help:
	@echo 'NeoTalkie: make setup | build | test | simulator | install SERIAL=...'
	@echo 'UI: make ui-generate | ui-visual [BASE=HEAD] | ui-accept REASON="..."'
setup:
	npm --prefix tools ci --ignore-scripts
android-setup:
	python3 android/scripts/setup.py
browser-setup:
	cd tools && npx --no-install playwright install chromium
build: android-build
android-build:
	python3 android/scripts/build.py
test: android-test ui-check check-public
android-test:
	python3 android/scripts/test.py
ui-generate:
	npm --prefix tools run ui:generate
ui-check:
	npm --prefix tools run ui:check
ui-visual:
	if [ -n "$$BASE" ]; then npm --prefix tools run ui:visual -- --base "$$BASE"; else npm --prefix tools run ui:visual; fi
ui-accept:
	@test -n "$$REASON" || (echo 'REASON is required'; exit 1)
	npm --prefix tools run ui:accept -- --reason "$$REASON"
check-public:
	python3 tools/scripts/check-public.py
simulator:
	npm --prefix tools run simulator
install:
	@test -n "$$SERIAL" || (echo 'SERIAL is required'; exit 1)
	python3 android/scripts/device.py --serial "$$SERIAL" install
