#!/usr/bin/env bash
# Prepares the source tree for building a single-language variant of the keyboard.
# Usage: rr/apply-variant.sh pl|en   then   ./gradlew assembleDebug -PrrLanguage=pl|en
# Sets app name and provider authorities of the debug build (they must be different for apps
# installed next to each other) and removes the bundled dictionaries of other languages.
set -euo pipefail

language="${1:?usage: rr/apply-variant.sh pl|en}"
case "$language" in
  pl) app_name="Klawiatura PL" ;;
  en) app_name="Klawiatura EN" ;;
  *) echo "unknown language: $language" >&2; exit 1 ;;
esac

cd "$(dirname "$0")/.."
values=app/src/debug/res/values
app_id="helium314.keyboard.$language.debug"

cat > "$values/strings.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="english_ime_name" translatable="false">$app_name</string>
    <string name="spell_checker_service_name" translatable="false">$app_name – sprawdzanie pisowni</string>
    <string name="ime_settings" translatable="false">$app_name – ustawienia</string>
</resources>
XML
cat > "$values/gesture_data.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="gesture_data_provider_authority" translatable="false">$app_id.provider</string>
</resources>
XML
cat > "$values/clip_provider.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="clipboard_provider_authority" translatable="false">$app_id.clipprovider</string>
</resources>
XML

# keep only dictionaries of this language (main_pl.dict, main_en-US.dict, ...)
for dict in app/src/main/assets/dicts/main_*.dict; do
  name="$(basename "$dict" .dict)"
  dict_language="${name#main_}"
  dict_language="${dict_language%%-*}"
  if [ "$dict_language" != "$language" ]; then rm "$dict"; fi
done

echo "prepared variant '$app_name' ($app_id), dictionaries:"
ls app/src/main/assets/dicts
