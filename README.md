<div align="center">

# 📞 CHAMAN Call Log Editor

**Apni call history. Apne control mein.**

*Saaf-suthra, monochrome, premium Android app jo tumhari call history ko dekhne aur manage karne ka kaam asaan bana deta hai.*

`Kotlin` · `Native Android` · `No ads` · `No internet`

</div>

---

## ✨ Features

| | |
|---|---|
| 🗂️ **Call history** | Phone ki saari call history ek clean list mein, latest sabse upar |
| 🔍 **Smart search** | Number ya contact ke **naam** se turant search |
| 🎛️ **Filters** | All · Incoming · Outgoing · Missed |
| 👤 **Contact names** | Saved contact ka naam number ke saath dikhta hai |
| 🧮 **Grouped calls** | Same number ki Missed / Rejected / Blocked calls ek card mein: `MISSED × 5` |
| ✏️ **Edit** | Number, type, date, time, duration sab badal sakte ho |
| ➕ **New entry** | Naya record khud bana sakte ho (5 types: Incoming, Outgoing, Missed, Rejected, Blocked) |
| 🕐 **Analog clock picker** | Time ghadi ke dial se chuno, date calendar se |
| 📋 **Copy** | Kisi bhi record ko copy karke naya bana lo |
| 👆 **Swipe actions** | Right swipe = Edit · Left swipe = Delete |
| ☑️ **Multi-select** | Card ko long press karo, kai records ek saath delete karo |
| ↩️ **Undo** | Delete ke baad 5 second tak wapas laane ka option |
| 🎨 **Type icons** | Har call type ka chhota monochrome icon |

---

## ⚙️ Kaise kaam karta hai

- App Android ke apne **Call Log** se seedha padhta aur likhta hai. Alag koi private database nahi.
- **Missed / Rejected / Blocked** calls mein "X Times" daalo, to app utne alag-alag asli records banata hai, aur list mein unhe ek card mein dikhata hai.
- Group ka count badhao ya ghatao, to app records add ya delete karke count barabar kar deta hai.
- Contact naam **READ_CONTACTS** permission se aate hain. Ye optional hai, na do to sirf number dikhega.
- Sab kuch tumhare phone par hota hai. Koi data bahar nahi jaata, internet ki zaroorat nahi.

---

## 🚀 Kaise use karein

1. **Install** karo aur app kholo.
2. Pehli baar **Call log** (padhna + likhna) aur **Contacts** ki permission allow karo.
3. List mein apni calls dikhengi. Upar search aur filter chips use karo.
4. **Edit:** card par `EDIT` dabao ya card ko right swipe karo.
5. **Delete:** `DELETE` dabao ya left swipe karo. Galti ho gayi? **UNDO** dabao.
6. **Kai records delete:** card ko long press karo, baaki cards tap karke select karo, phir `DELETE`.
7. **Naya record:** neeche `+ NEW ENTRY` dabao, number daalo, type chuno, date/time set karo.
   - Missed / Rejected / Blocked chuno to **X Times** daalo.
   - Incoming / Outgoing chuno to **duration** daalo.
8. **Copy:** card par `COPY` dabao, form pehle se bhara khulega, badlo aur save karo.

---

## 💡 Kyu kaam dega

- ⚡ **Tez aur halka:** koi bhaari library nahi, seedha native app.
- 🧼 **Clean design:** monochrome, bina bheed-bhaad ke, aankhon ko sukoon.
- 🔒 **Privacy:** sab kuch phone ke andar, na internet, na ads.
- 🧠 **Smart grouping:** 20 missed calls ke 20 card nahi, ek hi card `× 20`.
- 🛡️ **Safe:** delete se pehle confirmation, aur baad mein undo.
- 🤝 **Honest:** agar Android ya phone company kisi change ko rok de, to app saaf bata deti hai, jhooth nahi bolti.

---

## 👥 Kin logon ke liye

- 🧹 Jo apni call history **saaf-suthri** rakhna chahte hain (duplicate, galat entries hatana)
- 🛠️ **Developers aur testers** jinhe apne app ke liye demo/test call records chahiye
- 🎓 Jo seekhna chahte hain ki Android ka **Call Log provider** kaise kaam karta hai
- 📱 Jo ek simple, sundar aur bina-ads wala call manager chahte hain

---

## ⚠️ Zaroori baat

- Ye app tumhari **apni device** ki call history ke liye hai.
- Manually banaya record **asli call ka saboot nahi** hota. Kisi ko dhokha dene ya jhooth ke liye use mat karo.
- Kuch phones (company ki restrictions ki wajah se) call log mein likhne ya delete karne se rok sakte hain. Aise mein app error dikhayegi.

---

<div align="center">

**Made with ❤️ by Chaman Bhai Production**

</div>

## Dark mode (v08)
- Header button "◐" cycles System → Light → Dark (saved in `calllog_ui` prefs). Default = follow system.
- All colours come from `applyPalette()` in MainActivity; dialogs/date picker use `AppLight` / `AppDark` (res/values/styles.xml).
- Written without a compiler: first CI build is the first compile check.
