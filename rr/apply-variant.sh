#!/usr/bin/env bash
# Prepares the source tree for building the own keyboard variant with a limited set of languages.
# Usage: rr/apply-variant.sh pl,en   then   ./gradlew assembleDebug -PrrLanguages=pl,en
# Sets app name and provider authorities of the debug build (they must differ from other HeliBoard
# builds installed next to it) and removes the bundled dictionaries of other languages.
set -euo pipefail

languages="${1:?usage: rr/apply-variant.sh pl,en}"
app_name="Klawiatura"
app_id="helium314.keyboard.rr.debug"

cd "$(dirname "$0")/.."
values=app/src/debug/res/values

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

# keep only dictionaries of these languages (main_pl.dict, main_en-US.dict, ...)
for dict in app/src/main/assets/dicts/main_*.dict; do
  name="$(basename "$dict" .dict)"
  dict_language="${name#main_}"
  dict_language="${dict_language%%-*}"
  case ",$languages," in
    *",$dict_language,"*) ;;
    *) rm "$dict" ;;
  esac
done

echo "prepared '$app_name' ($app_id) with languages $languages, dictionaries:"
ls app/src/main/assets/dicts
