# Zapret KVN — политика форматов импорта

> Каноническая граница импортера. Документ определяет, какие входные форматы
> допустимы, что именно они меняют и при каких условиях можно расширять MVP.

| Поле | Значение |
|---|---|
| Статус | Clash YAML исследован и отложен; новые extended URI требуют реальных образцов |
| Последний аудит | 22 июля 2026 года |
| Ядро | `sing-box-extended` `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Источник сетевой истины | Только итоговый sing-box JSON профиля |

## Неподвижная граница

Любой импорт заканчивается созданием настоящего sing-box JSON до preview и
сохранения. YAML, URI и subscription response — только входные данные, а не
второй формат профиля или маршрутизации.

- импорт не переносит чужие DNS, route rules или proxy groups скрыто;
- неизвестное поле или тип нельзя молча отбросить и показать импорт успешным;
- исходный YAML не хранится и не участвует в runtime;
- перед сохранением итог проходит native `Libbox.CheckConfig()`;
- URL обновляется только вручную, без фонового scheduler;
- секреты маскируются в preview, ошибках, логах и диагностике.

## Форматы MVP

- полный sing-box JSON;
- JSON/raw/base64 subscription, который уже распознаёт реализованный importer;
- VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC URI;
- URL, QR, буфер обмена и системный file picker как способы доставки тех же
  данных.

## F-IMPORT-01 — Clash YAML

### Что подтверждено

Pinned source уже содержит общий subscription parser и вызывает парсеры
sing-box, Clash YAML, SIP008 и raw URI именно в таком порядке. Clash parser
читает только верхнеуровневый список `proxies` и преобразует его в sing-box
outbounds. Он не является импортом Clash DNS, rules или proxy groups:

- [общий parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/parser.go);
- [Clash parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/clash/parser.go).

Реальный сигнал спроса есть, но частичный импорт опасен: отдельно зафиксированы
запрос VLESS XHTTP из Mihomo YAML и случай, когда потеря ECH-параметра делала
импортированный сервер нерабочим:

- [NekoBox: Clash YAML с VLESS XHTTP](https://github.com/qr243vbi/nekobox/issues/228);
- [NekoBox: потеря ECH config при разборе Clash YAML](https://github.com/MatsuriDayo/NekoBoxForAndroid/pull/1173).

При этом собранный из точного commit Android `libbox.aar` не экспортирует
`ParseSubscription` или `ParseClashSubscription`. Повторять parser и его YAML
семантику на Kotlin означало бы добавить второй конвертер, новую зависимость и
риск расхождения с ядром.

### Решение MVP

Clash YAML не поддерживать. Не добавлять Kotlin YAML parser и не угадывать
значение неподдержанных полей. Если вход похож на YAML, показывать явное
сообщение «Clash YAML пока не поддерживается», а не общую ошибку JSON.

Вернуться к реализации можно только если выполнено всё следующее:

1. Parser точного ядра экспортирован через libbox, либо отдельная ADR явно
   разрешила ровно один поддерживаемый converter boundary.
2. Собрано минимум 10 обезличенных реальных subscriptions, включая VLESS
   XHTTP, ECH, Hysteria2, TUIC, Shadowsocks plugin, YAML anchors/aliases и разные
   типы scalar values.
3. Для каждого неподдержанного proxy type или значимого поля импорт завершается
   явной ошибкой; silent drop запрещён.
4. Golden-тест сравнивает полученные outbounds, а итог проходит native
   `CheckConfig()` и реальное подключение.
5. Clash `rules`, `dns`, `proxy-groups` и `rule-providers` остаются вне импорта.

## F-IMPORT-02 — дополнительные extended URI

[Link parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/link/parser.go)
распознаёт `tuic`, `trojan`, `vless`, `hysteria`, `hy2`, `hysteria2`, `ss` и
`vmess`. Из этого списка в приложении пока отсутствует только Hysteria v1
(`hysteria://`). Это кандидат, а не разрешение немедленно добавить протокол.

До нового протокола приоритетнее закрывать подтверждённые реальными ссылками
пробелы уже заявленных форматов: VLESS XHTTP, Shadowsocks plugin, aliases и
дополнительные параметры TUIC/Hysteria2. AnyTLS, SSH, ShadowTLS, WireGuard и
другие outbounds ядра не считаются URI-форматами только потому, что существуют
в JSON: без зафиксированного URI contract их синтаксис не придумывается.

Hysteria v1 можно включить, когда есть минимум три обезличенных реально
неподдержанных URI от пользователей, golden-тесты всех встреченных вариантов,
native `CheckConfig()` и device connection test. Сбор образцов — только после
явного действия пользователя, без аналитики; credentials удаляются до
диагностического экспорта.

SIP008 рассматривается отдельно: pinned core умеет этот JSON subscription
format, но это не extended URI. Его также нельзя объявлять поддержанным без
реальных образцов и доступного libbox binding.

## Таблица решений

| Формат | Статус | Следующий gate |
|---|---|---|
| Clash YAML `proxies` | Отложен, не входит в MVP | libbox binding + 10 реальных образцов + отсутствие silent drop |
| Hysteria v1 URI | Первый кандидат | 3 обезличенных URI + native/device tests |
| Расширения текущих URI | Приоритет по фактическим ошибкам | Реальный образец + golden/native test на каждый вариант |
| SIP008 | Отложен отдельно от URI | Реальные subscriptions + libbox binding |
| Прочие extended outbounds | Не определены как URI | Публичный contract и реальные неподдержанные ссылки |

Checkbox исследовательского пункта означает завершённый аудит, а не наличие
формата в приложении. Реальная поддержка отмечается только отдельной задачей и
после прохождения её gate.
