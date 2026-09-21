# Humogram — Maxfiylik siyosati / Политика конфиденциальности / Privacy Policy

**Oxirgi yangilanish / Последнее обновление / Last updated: 2026-09-21**

<!--
  This is the USER-FACING policy and the reviewed text behind the published
  page at https://hg.skycoax.uz/privacy (the address in jac_privacy_url and in
  the Play listing, built from privacy/index.html in the landing site source).
  This file and that page must say the same thing: change both in the same
  sitting and move "last updated" in both.

  As shipped on Google Play, the security scanner runs ENTIRELY ON THE DEVICE
  and transmits nothing: ScannerBootstrap builds the engine with no baseUrl and
  no tokens, so the backend branch is unreachable (BackendTokens is dead code,
  jac_api_base_url is unused). A server-assisted mode exists in the project
  (docs/PRIVACY.md + the backend/ service describe that server and its tests),
  but it is NOT wired into the published app, so this user-facing policy
  describes the on-device behaviour the binary actually has. If the backend is
  ever enabled in a shipped build, restore the "what is sent / who we share
  with / retention" sections and re-align docs/PRIVACY.md, the Play Data safety
  form, and this file together.

  tools/preflight.mjs fails the build if the <CONTACT-EMAIL>, <OPERATOR-NAME>
  or <SOURCE-URL> placeholders ever come back.
-->

---

<a name="uz"></a>

## O'zbekcha

### 1. Kirish

Humogram — Telegram platformasi asosida ishlaydigan messenjer bo'lib, unga
fayllar va havolalarni **qurilmangizda** tekshiradigan xavfsizlik skaneri
qo'shilgan.

- **Skanerimiz hech narsa yig'maydi va yubormaydi.** Fayllar, ularning raqamli
  izi (SHA-256) va havolalar faqat telefoningizda tekshiriladi.
- **Messenjer sifatida** ilova Telegram'dan foydalanadi, shuning uchun aloqa
  ma'lumotlaringiz Telegram serverlarida ishlanadi.

### 2. Ma'lumotlaringiz uchun kim javobgar

- **Xabar almashish — Telegram Messenger (Telegram LLC / FZ-LLC).** Hisob va
  suhbat ma'lumotlari Telegram tizimi orqali o'tadi va o'sha yerda saqlanadi:
  <https://telegram.org/privacy>.
- **Xaridlar — Google (Google Play).** Obuna va xaridlar Google Play orqali.
- **Ilova va skaner — dasturchi (Kamolov Muxammad).** Chiqarilgan versiyada
  dasturchi sizning ma'lumotlaringizni qabul qiladigan server yuritmaydi.

### 3. Qanday ma'lumotlar ishlanadi

Humogram Telegram mijozi bo'lgani uchun quyidagilar **Telegram serverlariga**
yuboriladi (rasmiy Telegram ilovasidagidek):

| Ma'lumot | Kim ishlaydi | Qachon |
|---|---|---|
| Telefon raqami | Telegram | Hisobga kirish (majburiy) |
| Ism, foydalanuvchi nomi, bio, profil rasmi | Telegram | Profil |
| Ikki bosqichli himoya uchun e-pochta | Telegram | Agar o'rnatsangiz |
| Kontaktlar | Telegram | Kontakt sinxronizatsiyasi yoqilsa |
| Oddiy chatlardagi xabar, media, hujjat, ovozli xabar, qo'ng'iroq | Telegram | Xabar almashganda |
| Joylashuv | Telegram | Agar ulashsangiz |
| Xaridlar (Premium, Stars, sovg'alar) | Google Play → Telegram | Xarid qilganda |

**Maxfiy chatlar** uchdan-uchgacha shifrlangan va faqat qurilmalaringizda qoladi.

**Xavfsizlik skaneri** butunlay qurilmangizda ishlaydi va serverga fayllar,
raqamli izlar, fayl nomlari yoki havolalarni **yubormaydi**.

### 4. Ma'lumotlar nima uchun ishlatiladi

Xabar almashish, qo'ng'iroq va fayl uzatish (Telegram orqali); kontaktlardan
tanishlarni topish; obuna va xaridlar (Google Play); zararli fayl va
havolalardan himoya (faqat qurilmada); xizmat xavfsizligi.

### 5. Uchinchi tomonlarga uzatish

Biz ma'lumotlaringizni sotmaymiz. Ilovadan foydalanish aloqa ma'lumotlarini
**Telegram LLC**'ga, xarid ma'lumotlarini **Google**'ga yuboradi. Qurilmadagi
skaner hech kimga hech narsa uzatmaydi.

### 6. Ma'lumotlar qancha saqlanadi

Dasturchi hech qanday shaxsiy ma'lumot saqlamaydi. Aloqa ma'lumotlari
Telegram'da (uni Telegram'dan o'chirishingiz mumkin), xaridlar Google va
Telegram'da saqlanadi. Skaner ishchi ma'lumotlari faqat qurilmada.

### 7. Sizning huquqlaringiz

Telegram hisobingizni o'chirishingiz mumkin (<https://my.telegram.org/auth?to=delete>);
xaridlarni Google Play orqali boshqarasiz; savollar bo'yicha biz bilan bog'laning.

### 8. Buni qanday tekshirish mumkin

Ilova ochiq kodli (GPLv3): <https://github.com/skycoax/Humogram>. Loyihada
serverli tekshiruv rejimi ham bor, lekin Google Play'dagi ilovada u yoqilmagan.

### 9. Aloqa

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="ru"></a>

## Русский

### 1. Введение

Humogram — мессенджер на базе Telegram со встроенным сканером безопасности,
который **работает на вашем устройстве**.

- **Наш сканер ничего не собирает и не отправляет.** Файлы, их цифровой
  отпечаток (SHA-256) и ссылки проверяются только на вашем телефоне.
- **Как мессенджер** приложение использует Telegram, поэтому данные вашей
  переписки обрабатываются на серверах Telegram.

### 2. Кто отвечает за ваши данные

- **Переписка — Telegram Messenger (Telegram LLC / FZ-LLC).** Данные аккаунта и
  переписки проходят через систему Telegram и хранятся там:
  <https://telegram.org/privacy>.
- **Покупки — Google (Google Play).**
- **Приложение и сканер — разработчик (Kamolov Muxammad).** В опубликованной
  версии разработчик не располагает сервером, получающим ваши данные.

### 3. Какие данные обрабатываются

Так как Humogram — клиент Telegram, следующее отправляется на **серверы
Telegram** (как в официальном приложении):

| Данные | Кто обрабатывает | Когда |
|---|---|---|
| Номер телефона | Telegram | Вход в аккаунт (обязательно) |
| Имя, имя пользователя, «о себе», фото профиля | Telegram | Профиль |
| E-mail для двухэтапной проверки | Telegram | Если задан |
| Контакты | Telegram | При синхронизации контактов |
| Сообщения, медиа, документы, голосовые, звонки в обычных чатах | Telegram | При переписке |
| Геолокация | Telegram | Если вы ею делитесь |
| Покупки (Premium, Stars, подарки) | Google Play → Telegram | При покупке |

**Секретные чаты** защищены сквозным шифрованием и остаются только на ваших
устройствах.

**Сканер безопасности** работает полностью на устройстве и **не отправляет** на
сервер файлы, цифровые отпечатки, имена файлов или ссылки.

### 4. Для чего используются данные

Переписка, звонки и передача файлов (через Telegram); поиск знакомых среди
контактов; подписки и покупки (Google Play); защита от вредоносных файлов и
ссылок (только на устройстве); безопасность сервиса.

### 5. Передача третьим сторонам

Мы не продаём ваши данные. Использование приложения отправляет данные переписки
в **Telegram LLC**, данные о покупках — в **Google**. Сканер на устройстве не
передаёт никому ничего.

### 6. Сколько храним

Разработчик не хранит персональных данных. Данные переписки — в Telegram (можно
удалить в Telegram), покупки — у Google и Telegram. Рабочие данные сканера —
только на устройстве.

### 7. Ваши права

Вы можете удалить аккаунт Telegram (<https://my.telegram.org/auth?to=delete>);
управлять покупками через Google Play; связаться с нами по вопросам.

### 8. Как это проверить

Приложение с открытым кодом (GPLv3): <https://github.com/skycoax/Humogram>. В
проекте есть режим проверки с участием сервера, но в приложении из Google Play
он не включён.

### 9. Связь

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="en"></a>

## English

### 1. Introduction

Humogram is a messenger built on the Telegram platform, with a built-in security
scanner that **runs on your device**.

- **Our scanner collects and sends nothing.** Files, their fingerprint
  (SHA-256) and links are checked only on your phone.
- **As a messenger**, the app uses Telegram, so your messaging data is processed
  on Telegram's servers.

### 2. Who is responsible for your data

- **Messaging — Telegram Messenger (Telegram LLC / FZ-LLC).** Account and
  conversation data passes through and is stored on Telegram's system:
  <https://telegram.org/privacy>.
- **Purchases — Google (Google Play).**
- **The app and scanner — the developer (Kamolov Muxammad).** In the published
  build, the developer operates no server that receives your data.

### 3. What data is processed

Because Humogram is a Telegram client, the following is sent to **Telegram's
servers** (the same as the official app):

| Data | Processed by | When |
|---|---|---|
| Phone number | Telegram | Sign in (required) |
| Name, username, bio, profile photo | Telegram | Profile |
| Two-step verification email | Telegram | If set |
| Contacts | Telegram | If contact sync is on |
| Messages, media, documents, voice, calls in normal chats | Telegram | When messaging |
| Location | Telegram | If you share it |
| Purchases (Premium, Stars, gifts) | Google Play → Telegram | When you buy |

**Secret chats** are end-to-end encrypted and stay only on your devices.

**The security scanner** runs entirely on the device and does **not** send files,
fingerprints, file names or links to any server.

### 4. How data is used

Messaging, calls and file transfer (via Telegram); finding people you know among
your contacts; subscriptions and purchases (Google Play); protection from
malicious files and links (on the device only); service security.

### 5. Sharing with third parties

We do not sell your data. Using the app sends messaging data to **Telegram LLC**
and purchase data to **Google**. The on-device scanner discloses nothing to
anyone.

### 6. Data retention

The developer stores no personal data. Messaging data is retained by Telegram
(you can delete it in Telegram); purchases by Google and Telegram; the scanner's
working data stays only on your device.

### 7. Your rights

You can delete your Telegram account (<https://my.telegram.org/auth?to=delete>);
manage purchases through Google Play; and contact us with questions.

### 8. How to check this

The app is open source (GPLv3): <https://github.com/skycoax/Humogram>. The
project also contains a server-assisted scan mode, but it is not enabled in the
app published on Google Play.

### 9. Contact

kamolov1575@gmail.com · Kamolov Muxammad
