# Zapret KVN Android

Нативный Material 3 клиент для `sing-box-extended` с per-app VPN, импортом ссылок/подписок и настоящим sing-box JSON как единственным источником сетевой конфигурации.

Этапы 0–7 и автоматизируемая часть этапа 8 реализованы. Приложение принимает JSON, URL, QR, буфер и файлы, поддерживает основные URI/subscription форматы, per-app VPN, routing, session-dashboard и redacted диагностику. Локально проходят 85/85 JVM и полный набор 67/67 instrumented-тестов на API 36; API 26 прошёл прежнюю полную матрицу 66/66 и новый security-delta 3/3, API 29 — 66/66. API 29/36 дополнительно прошли 100 connect/stop и 50 Wi-Fi/cellular transitions. API 36 также прошёл 16 performance-сценариев × 5 повторов с raw System Trace/batterystats; defaults не изменены, поскольку AVD не заменяет физический energy gate. Физические DNS/OEM/NAT64/outbound/energy gates, production signing key и первый production GitHub Release ещё впереди. Канонические документы:

- [архитектура](ARCHITECTURE.md);
- [план реализации](IMPLEMENTATION_PLAN.md);
- [DNS ADR](DNS_ARCHITECTURE.md);
- [Routing ADR](ROUTING_ARCHITECTURE.md);
- [политика форматов импорта](IMPORT_FORMATS.md).
- [подпись и восстановление ключа](SIGNING.md).
- [протокол Gate 8](GATE8_RESULTS.md).

## Toolchain

- JDK 17 для воспроизводимой сборки libbox;
- Android Gradle Plugin `9.2.1`;
- Gradle `9.4.1`;
- compile/target SDK `36`, min SDK `26`;
- Compose BOM `2026.06.00`;
- pinned `sing-box-extended` `v1.13.14-extended-2.5.2` commit `ff11f007ec798136a5de258f947a4f34011a37ea`.
- release ABI: `arm64-v8a`; build AAR дополнительно содержит `x86_64` только для instrumented-тестов эмулятора и отсекается release `abiFilters`.

## Сборка

Укажите путь к Android SDK в `local.properties` или `ANDROID_HOME`. Полный локальный аналог CI запускается одной командой:

```bash
scripts/ci-build.sh
```

Команда собирает exact pinned CLI/AAR, проверяет 6 fixtures и packaged rule-set, запускает Go/Kotlin tests и lint, затем собирает debug/release APK и выполняет security-аудит уже собранного release. Вместе с APK создаются R8 mapping и exact native symbols, проверенные по всем allocated ELF-секциям production `libbox`. Debug APK появится в `app/build/outputs/apk/debug/`. Сборка приложения намеренно останавливается, если `app/libs/libbox.aar` отсутствует. Динамическая загрузка ядра приложением запрещена.

GitHub Actions запускает тот же `scripts/ci-build.sh` при push, pull request или вручную и сохраняет APK, SHA-256, core version/revision, R8 mapping, native symbols и security report одним artifact.

Release workflow запускается вручную для существующего tag `vMAJOR.MINOR.PATCH` или
`vMAJOR.MINOR.PATCH-beta.N` после физического gate. Он требует approval и secrets среды
`release`, сверяет закреплённый SHA-256 signing-сертификата, повторяет Android/no-background
gates, подписывает arm64 APK и публикует APK, `.sha256`, `release-metadata.json` и notes.
Подготовка постоянного ключа описана в [SIGNING.md](SIGNING.md).

Полная Android-матрица запускается на одном подключённом устройстве командой
`./gradlew :app:connectedDebugAndroidTest`. Контракт hard process death отдельно
проверяет `scripts/verify-process-recreation.sh`; debug probe в release APK не попадает.
Same-key upgrade/data persistence проверяет `scripts/verify-same-key-upgrade.sh`.
Minified release clean-install/update, cold start, downgrade и несовместимую подпись проверяет
`scripts/verify-release-device.sh` на одном подключённом arm64-v8a/x86_64 устройстве; скрипт
использует одноразовый тестовый ключ и не заменяет финальную проверку production-подписи.

Gradle Wrapper проверяет SHA-256 дистрибутива, Maven/plugin artifacts проверяются по `gradle/verification-metadata.xml`, а используемые GitHub Actions закреплены полными commit SHA.

## Тестовые сборки

Ручные debug-сборки публикуются в [GitHub Releases](https://github.com/youtubediscord/ZapretKVN-android/releases)
как pre-release. Они имеют package `io.github.zapretkvn.android.debug` и Android debug key,
могут стоять рядом с будущим production APK и не считаются прохождением release gate.
При ошибке откройте «Настройки → Диагностика → Создать и передать diagnostic JSON».

## Известные ограничения MVP

- Android 8+; release APK содержит только `arm64-v8a`, интерфейс рассчитан на телефоны.
- Always-on/Lockdown и shared UID не поддерживаются как гарантированный per-app режим.
- Managed DNS перехватывает TCP/UDP 53, но не встроенный DoH, DoT или mDNS; FakeIP выключен.
- Domain-only block не является firewall: для гарантии нужен IP/CIDR rule-set.
- Clash YAML и Hysteria v1 URI пока не импортируются; raw JSON остаётся ответственностью пользователя.
- Подписки, core и APK обновляются только вручную; silent install и фоновая синхронизация отсутствуют.
- При неработающем proxy/DNS VPN закрывается без бесконечного retry и plaintext DNS fallback.

## Лицензия

Код Zapret KVN распространяется по GPL-3.0-or-later. Условия сторонних компонентов перечислены в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
