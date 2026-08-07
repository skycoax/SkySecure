# SkySecure

Неофициальный клиент Telegram для Android со встроенной проверкой файлов и
ссылок. Форк [Telegram-Android](https://github.com/DrKLO/Telegram).

Сделан для Узбекистана, где вредоносные программы расходятся между людьми прямо
в мессенджере — поддельные банковские приложения, `.apk` под видом фотографий, —
и где фишинговых ссылок заметно больше, чем самих файлов.

**Не связан с Telegram, не одобрен и не управляется Telegram.**
Приложение использует Telegram API.

## Лицензия и исходный код

GPL-3.0-only, как и upstream. Это дерево и есть полный исходный код
распространяемых сборок.

Логика сканера вынесена в отдельный репозиторий:
[skycoax/TelegramAPI](https://github.com/skycoax/TelegramAPI) — там же бэкенд,
общие данные и документация. Он подключается как Gradle-модуль
`:jacsecure-core`, см. `settings.gradle`.

## Что изменено относительно upstream

| Где | Что |
|---|---|
| `FileLoadOperation.java` | Потоковый SHA-256 по мере скачивания |
| `FileLoader.java` | Перехват завершённой загрузки перед показом в UI |
| `BuildVars.java` | `api_id`/`api_hash` читаются из `BuildConfig`, не зашиты |
| `ApplicationLoader.java` | Одна строка: `ScannerBootstrap.install(this)` |
| `AndroidManifest.xml` | `networkSecurityConfig` и два экрана |
| `uz/jac/secure/android/` | Классы сканера |
| `assets/` | Общие данные: список брендов, PSL, сигнатуры |

Диффы против upstream намеренно маленькие: чем меньше правок в чужих файлах,
тем дешевле ребейз на новый релиз Telegram.

## Сборка

Нужны свои `api_id` / `api_hash` с <https://my.telegram.org>:

```bash
cp ../TelegramAPI/client/telegram-integration/gradle/credentials.properties.example credentials.properties
# заполните, файл в .gitignore
./gradlew :TMessagesProj_App:assembleDebug
```

Сборка **не пройдёт** без них, и это намеренно: значения по умолчанию в upstream
принадлежат Telegram, использование их сторонним клиентом ведёт к отзыву ключа,
а отзыв забирает доступ к аккаунтам у всех, кто установил сборку.

Для релизной сборки нужен свой keystore:

```bash
node ../TelegramAPI/tools/gen-keystore.mjs --out ~/skysecure-release.jks
```

## Что ещё не сделано

Проверяется командой:

```bash
node ../TelegramAPI/tools/preflight.mjs --fork .
```

- **Пины сертификатов банков** — без них подделки банковских приложений
  определяются только по имени и разрешениям, но не по подписи.
- **Пиннинг TLS** — домен прописан, пины ещё placeholder.
- **`google-services.json`** — сейчас в дереве лежит файл Telegram из upstream.
  Push-уведомления не будут работать, пока вы не подставите свой проект Firebase.
