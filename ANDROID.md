# Riff for Android (Fork)

Полноценный порт музыкального плеера **[Riff](https://github.com/rootscripts/riff)** для операционной системы Android.

---

## 📱 Архитектура порта

Riff изначально разработан на базе Electron, Vanilla JS, CSS3 и Web Audio API. Для Android реализована гибридная нативная архитектура:

1. **Интерфейс (UI)**:
   - Встроен в Android WebView с аппаратным ускорением GPU (`hardwareAccelerated="true"`), поддержкой Web Audio API и WebGL.
   - Сохранён оригинальный дизайн Material Design 3, анимации органических форм, эквалайзер, визуализатор и караоке-режим текстов песен.
   - Адаптирован под экраны мобильных устройств (убран drag заголовок десктопного окна, добавлена поддержка safe-area для вырезов и челок, оптимизировано сенсорное управление).

2. **Android Bridge (`android-bridge.js` & `RiffBridge.kt`)**:
   - Эмулирует объект `window.electronAPI` для работы без модификации базовой логики приложения.
   - Интегрирует события воспроизведения с системными сервисами Android.

3. **Фоновое воспроизведение и шторка уведомлений (`RiffAudioService.kt`)**:
   - `ForegroundService` типа `mediaPlayback`.
   - Системное уведомление с отображением текущего трека, исполнителя и кнопками управления: **Play / Pause / Next / Previous**.
   - Управление аудиофокусом (`AudioManager`) и автоматическая пауза при отключении наушников (`ACTION_AUDIO_BECOMING_NOISY`).

4. **Встроенный резолвер и локальный сервер (`RiffServer.kt` & `MusicResolver.kt`)**:
   - Локальный HTTP-сервер на `127.0.0.1:8888`.
   - Поддерживает endpoints: `/api/search`, `/api/stream-info`, `/api/lyrics`, `/api/artist`, `/api/hot`, `/api/state`, `/api/tools-status`.
   - Поиск музыки через YouTube Music (InnerTube Web Remix API) и Deezer.
   - Синхронизированные построчные тексты караоке через LRCLIB.

---

## 🛠 Сборка APK

Для сборки проекта из консоли Windows:

```powershell
cd C:\Users\Camille\Documents\projects\riff-android
.\gradlew.bat assembleDebug
```

Скомпилированный файл будет находиться в:
`android\app\build\outputs\apk\debug\app-debug.apk`

---

## 📲 Установка на Android-устройство

При подключённом телефоне по USB с включённой отладкой по USB:

```powershell
adb install -r "C:\Users\Camille\Documents\projects\riff-android\riff-42.3.0-android-debug.apk"
```

Или скопируйте файл `riff-42.3.0-android-debug.apk` на телефон и откройте его через любой проводник.

---

## 🐙 Публикация форка на GitHub

Чтобы выложить свой форк в свой GitHub-аккаунт:

1. Создайте новый репозиторий на GitHub (например, `riff-android`).
2. В консоли выполните:
```bash
cd C:\Users\Camille\Documents\projects\riff-android
git remote add myfork https://github.com/<your-username>/riff-android.git
git push -u myfork android
```
