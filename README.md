# WatchBridge

Powiadomienia z iPhone'a na zegarku z Wear OS (testowane pod OnePlus Watch 4), bez aplikacji na iPhonie.

## Jak to działa

iOS ma wbudowaną usługę **ANCS** (Apple Notification Center Service). Każde sparowane
akcesorium BLE może przez nią czytać powiadomienia z iPhone'a, a także odbierać i odrzucać połączenia.
Z ANCS korzystają Garminy i Amazfity. WatchBridge robi to samo na Wear OS:

1. Zegarek rozgłasza się po BLE z „solicitation” usługi ANCS.
2. iPhone łączy się z zegarkiem i oba urządzenia się parują (bonding).
3. Zegarek jako klient GATT subskrybuje *Notification Source* i *Data Source* i pobiera tytuł,
   treść, nazwę aplikacji oraz etykiety akcji.
4. Na zegarku pojawia się powiadomienie. Połączenia przychodzące otwierają pełnoekranowy
   ekran z przyciskami ✕ i ✓, a wibracja trwa, dopóki dzwonią.

## Budowanie

**GitHub Actions (najprościej):** wypchnij repo na GitHub. Zakładka *Actions* → *Build APK* →
artefakt `watchbridge-apk` zawiera `app-debug.apk`.

**Lokalnie (Lubuntu):** JDK 17, Android SDK (cmdline-tools, platforma 35), potem
```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

## Instalacja na zegarku

1. Wear OS na początek wymaga konfiguracji z telefonem z Androidem (wystarczy dowolny,
   pożyczony, i tylko raz). Potem ten telefon nie jest już potrzebny.
2. Na zegarku: *Ustawienia → System → Informacje → Wersje* → stuknij 7× w *Numer kompilacji*.
3. *Opcje programisty* → włącz **Debugowanie ADB** i **Debugowanie bezprzewodowe**.
4. Na komputerze w tej samej sieci Wi-Fi:
   ```bash
   sudo apt install adb
   adb pair  IP_ZEGARKA:PORT_PAROWANIA     # kod z ekranu „Sparuj nowe urządzenie”
   adb connect IP_ZEGARKA:PORT
   adb install -r app-debug.apk
   ```
5. Uprawnienia, których zegarek może nie dać zmienić z poziomu UI:
   ```bash
   # bez ubijania w tle
   adb shell dumpsys deviceidle whitelist +pl.etoo.watchbridge
   # pełnoekranowy ekran połączenia (Android 14+)
   adb shell appops set pl.etoo.watchbridge USE_FULL_SCREEN_INTENT allow
   ```

## Parowanie z iPhone'em

1. Uruchom WatchBridge na zegarku → *Start*, zaakceptuj uprawnienia. Status: „Czekam na iPhone'a…”.
2. iPhone: *Ustawienia → Bluetooth*. Zegarek powinien pojawić się w „Inne urządzenia”. Stuknij go.
   - Jeśli go tam nie ma, zainstaluj darmowe **nRF Connect** albo **LightBlue**, znajdź zegarek
     i połącz się z nim. Parowanie i tak będzie systemowe, a aplikację potem można usunąć.
3. Potwierdź parowanie na iPhonie (i na zegarku, jeśli zapyta).
4. iOS zapyta, czy udostępnić powiadomienia. Wybierz **Zezwól**. Jeśli przegapisz:
   *Bluetooth → (i) przy zegarku → „Udostępniaj powiadomienia systemowe”*.
5. Status na zegarku: „Połączono: iPhone”.

Później zegarek łączy się sam, gdy iPhone jest w zasięgu (autoConnect).

**WhatsApp:** w iOS *Ustawienia → Powiadomienia → WhatsApp → Pokaż podglądy: Zawsze*,
inaczej przyjdzie samo „Powiadomienie”.

## Debug

```bash
adb logcat -s WatchBridge
```

## Ograniczenia

- Tylko powiadomienia i akcje, jakie udostępnia ANCS (odbierz/odrzuć, wyczyść). Nie ma
  odpowiadania na wiadomości, synchronizacji zdrowia ani pogody.
- Przy „Odbierz” rozmowa i tak idzie przez iPhone'a, bo zegarek nie ma profilu HFP do iOS.
- W trybie oszczędzania energii OnePlusa (sam RTOS) Wear OS śpi, więc powiadomień nie będzie.
- Stare powiadomienia, które były już na iPhonie w chwili połączenia, są pomijane.

## Ikony aplikacji

ANCS nie przesyła obrazków, więc ikony siedzą w samej aplikacji na zegarku. Domyślnie każda
aplikacja z iPhone'a dostaje kolorowe kółko z symbolem kategorii (czat, telefon, mail, kalendarz, dzwonek).

Własną ikonę dodasz tak: wrzuć PNG (np. 128×128) do `app/src/main/res/drawable/` pod nazwą
`app_` + bundle ID z małych liter, a kropki zamień na `_`:

| Aplikacja | Nazwa pliku |
|---|---|
| WhatsApp | `app_net_whatsapp_whatsapp.png` |
| WhatsApp Business | `app_net_whatsapp_whatsappsmb.png` |
| Wiadomości (SMS) | `app_com_apple_mobilesms.png` |
| Telefon | `app_com_apple_mobilephone.png` |
| Messenger | `app_com_facebook_messenger.png` |
| Telegram | `app_ph_telegra_telegraph.png` |
| Signal | `app_org_whispersystems_signal.png` |
| Gmail | `app_com_google_gmail.png` |

Bundle ID innych aplikacji znajdziesz w logu: `adb logcat -s WatchBridge`.
Po dodaniu pliku: commit → push → nowe APK z Actions → `adb install -r`.
