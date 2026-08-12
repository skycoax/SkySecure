# Humogram — Maxfiylik siyosati / Политика конфиденциальности / Privacy Policy

**Oxirgi yangilanish / Последнее обновление / Last updated: 2026-08-07**

<!--
  This is the USER-FACING policy. docs/PRIVACY.md is the engineering document
  behind it — schema constraints, table shapes, the tests that fail if a claim
  here stops being true. Neither replaces the other:

    - PRIVACY.md says "telemetry_events has no foreign key to devices".
    - This file says "we cannot tell who received a file".

  They must never disagree. If you change one, change the other in the same
  commit, and check that backend/test/privacy.test.ts still passes — that suite
  is what makes these sentences enforceable rather than aspirational.

  This file must actually be reachable at the URL in jac_privacy_url, which
  points into the published fork — so a copy has to live there too. A policy
  the app links to but cannot serve is worse than no link at all: the link
  itself is a claim that the document exists.

  tools/preflight.mjs fails the build if the <CONTACT-EMAIL>, <OPERATOR-NAME>
  or <SOURCE-URL> placeholders ever come back.
-->

---

<a name="uz"></a>

## O'zbekcha

### Qisqacha

Humogram sizga kelgan fayllarni va havolalarni tekshiradi. Tekshiruv uchun
serverimizga **faylning raqamli izi** yoki **havolaning o'zi** yuboriladi.

**Hech qachon yuborilmaydi:** fayl nomlari, xabar matni, kim yuborgani, telefon
raqamingiz, kontaktlaringiz.

**Maxfiy chatlarda hech narsa yuborilmaydi.** U yerda tekshiruv faqat
telefoningizda bajariladi.

### Nima yuboriladi

| Ma'lumot | Yuboriladimi | Qachon |
|---|---|---|
| Faylning raqamli izi (SHA-256) | Ha | Faqat oddiy chatlarda |
| Fayl hajmi va turi | Ha | Tekshiruv so'rovi bilan birga |
| Xabardagi havola | Ha | Faqat havolaning o'zi |
| Faylning ichidagisi | Faqat siz ruxsat bersangiz | Har safar alohida so'raladi |
| Fayl nomi | **Yo'q** | Hech qachon |
| Xabar matni | **Yo'q** | Hech qachon |
| Kim yuborgani, chat nomi | **Yo'q** | Hech qachon |
| Telefon raqami, kontaktlar | **Yo'q** | Hech qachon |

### "Raqamli iz" nima degani

Bu fayldan hisoblanadigan uzun raqam. Undan faylni qayta tiklab bo'lmaydi — bu
faylning barmoq izi, fayl nusxasi emas. U bizga bu faylni ilgari zararli deb
topganmiz yoki yo'qligini bilish uchun kerak.

Biz uni kim yuborganini bilmaymiz: yozuvlarimizda qurilma yoki foydalanuvchi
bilan bog'lovchi hech narsa yo'q.

### Faylni chuqur tekshirish

Agar fayl haqida hech qanday ma'lumot topilmasa, biz sizdan **uning nusxasini**
yuborishni so'rashimiz mumkin. Bu har safar alohida so'raladi va siz rad
qilishingiz mumkin.

Yuborilgan fayl 24 soat ichida o'chiriladi, hech kimga berilmaydi va hech qanday
ommaviy antivirus xizmatiga joylanmaydi.

### Biz kimga ma'lumot beramiz

| Xizmat | Holati | Nima oladi |
|---|---|---|
| MalwareBazaar | Yoqilgan | Faqat raqamli iz |
| VirusTotal | O'chirilgan | Raqamli iz yoki havola. Fayl hech qachon |
| Google Safe Browsing | O'chirilgan | Havola |
| Kaspersky OpenTIP | O'chirilgan | Raqamli iz yoki havola |

### Qancha saqlanadi

- Raqamli iz va natija — muddatsiz (kimga tegishli ekani yozilmagan)
- Chuqur tekshiruvga yuborilgan fayl — 24 soatdan ko'p emas
- Server jurnallari — 30 kundan ko'p emas, havolalar ularga yozilmaydi

### Buni qanday tekshirish mumkin

Ilova ochiq kodli (GPLv3). Kodni ko'rib, bu yerda yozilganlar rostmi yoki
yo'qligini o'zingiz tekshirishingiz mumkin: https://github.com/skycoax/Humogram

### Aloqa

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="ru"></a>

## Русский

### Коротко

Humogram проверяет файлы и ссылки, которые вам присылают. Для проверки на наш
сервер уходит **цифровой отпечаток файла** или **сама ссылка**.

**Никогда не уходит:** имена файлов, текст сообщений, кто отправитель, ваш номер
телефона, ваши контакты.

**Из секретных чатов не уходит ничего.** Там проверка выполняется только на
вашем телефоне.

### Что отправляется

| Данные | Отправляется | Когда |
|---|---|---|
| Цифровой отпечаток файла (SHA-256) | Да | Только в обычных чатах |
| Размер и тип файла | Да | Вместе с запросом проверки |
| Ссылка из сообщения | Да | Только сама ссылка |
| Содержимое файла | Только с вашего разрешения | Спрашиваем каждый раз отдельно |
| Имя файла | **Нет** | Никогда |
| Текст сообщения | **Нет** | Никогда |
| Отправитель, название чата | **Нет** | Никогда |
| Номер телефона, контакты | **Нет** | Никогда |

### Что такое «цифровой отпечаток»

Это длинное число, вычисленное из файла. Восстановить файл из него нельзя — это
отпечаток пальца файла, а не его копия. Он нужен, чтобы узнать, встречали ли мы
этот файл раньше и признавали ли его вредоносным.

Мы не знаем, кто его прислал: в наших записях нет ничего, что связывало бы
отпечаток с устройством или человеком.

### Углублённая проверка

Если о файле нет никаких сведений, мы можем предложить отправить **его копию**.
Это спрашивается отдельно для каждого файла, и вы можете отказаться.

Отправленный файл удаляется в течение 24 часов, никому не передаётся и не
публикуется ни в одном общедоступном антивирусном сервисе.

### Кому мы передаём данные

| Сервис | Состояние | Что получает |
|---|---|---|
| MalwareBazaar | Включён | Только отпечаток |
| VirusTotal | Выключен | Отпечаток или ссылку. Файл — никогда |
| Google Safe Browsing | Выключен | Ссылку |
| Kaspersky OpenTIP | Выключен | Отпечаток или ссылку |

### Сколько храним

- Отпечаток и результат — бессрочно (без указания, чей он)
- Файл, отправленный на углублённую проверку — не более 24 часов
- Журналы сервера — не более 30 дней, ссылки в них не записываются

### Как это проверить

Приложение с открытым исходным кодом (GPLv3). Вы можете посмотреть код и
убедиться, что написанное здесь — правда: https://github.com/skycoax/Humogram

### Связь

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="en"></a>

## English

### In short

Humogram checks the files and links people send you. To do that, a **digital
fingerprint of the file** or **the link itself** is sent to our server.

**Never sent:** file names, message text, who sent it, your phone number, your
contacts.

**Nothing at all is sent from secret chats.** There, checks run only on your
phone.

### What is sent

| Data | Sent | When |
|---|---|---|
| File fingerprint (SHA-256) | Yes | Normal chats only |
| File size and type | Yes | With the check request |
| A link from a message | Yes | The link alone |
| File contents | Only if you allow it | Asked separately every time |
| File name | **No** | Never |
| Message text | **No** | Never |
| Sender, chat name | **No** | Never |
| Phone number, contacts | **No** | Never |

### What a "fingerprint" is

A long number computed from the file. The file cannot be reconstructed from it —
it is the file's fingerprint, not a copy. We use it to find out whether we have
seen that file before and judged it harmful.

We do not know who sent it: nothing in our records links a fingerprint to a
device or a person.

### Deep scan

If nothing is known about a file, we may offer to send **a copy of it**. This is
asked separately for each file, and you can decline.

A file sent this way is deleted within 24 hours, is not shared with anyone, and
is never published to any public antivirus service.

### Who we share with

| Service | State | What it receives |
|---|---|---|
| MalwareBazaar | On | A fingerprint only |
| VirusTotal | Off | A fingerprint or a link. Never a file |
| Google Safe Browsing | Off | A link |
| Kaspersky OpenTIP | Off | A fingerprint or a link |

### How long we keep it

- Fingerprint and result — indefinitely (with no record of whose it was)
- A file sent for deep scan — no more than 24 hours
- Server logs — no more than 30 days, and links are not written to them

### How to check this

The app is open source (GPLv3). You can read the code and verify that what is
written here is true: https://github.com/skycoax/Humogram

### Contact

kamolov1575@gmail.com · Kamolov Muxammad
