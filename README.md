# AirPay

**Because Signals > WiFi**

AirPay is an offline-first payment solution built by two third-year engineering students from Gurgaon who were frustrated by payment failures in low-signal zones. We bridged modern UPI systems with legacy GSM signaling to enable digital payments with just **one bar of signal**.

No internet required. Just a cellular network.

---

## 🚨 Problem Statement

Every UPI transaction assumes one thing: **internet connectivity**.

But across India, transactions fail daily in:

- Rural areas with weak or no internet
- Underground metros and tunnels
- Remote highways with signal drops
- Emergency situations and outages

Failed payments lead to:

- Lost sales for merchants
- Reduced trust in digital payments
- Fallback to cash

Meanwhile, cellular calling networks often still work.

> "Payments failed where calls worked. That gap didn’t make sense."  
> — Chirag & Meet, AirPay

---

## 💡 Our Solution

## UPI Over GSM Signaling

AirPay is an asynchronous, offline-tolerant payment orchestration layer that connects UPI workflows with GSM channels.

Using an intelligent Android accessibility service, AirPay automatically navigates telecom payment menus and enables transactions without mobile data.

---

## ⚙️ How It Works

```text
1. Scan QR / Enter UPI ID
2. Dial GSM Code
3. Navigate GSM Menu
4. User Enters MPIN
5. Transaction Complete
```
