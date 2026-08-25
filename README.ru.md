<p align="center">
  <img src="./zamolxis-icon.png" width="200" height="200" alt="Zamolxis" />
</p>

# Zamolxis

**[English](README.md) | Русский**

[![CI](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml/badge.svg)](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml)

Zamolxis — приложение для сообщений и голосовых звонков в сети [Reticulum](https://github.com/markqvist/Reticulum) на Android. Отправляйте сообщения [LXMF](https://github.com/markqvist/LXMF) и совершайте звонки [LXST](https://github.com/markqvist/LXST/tree/master/LXST) без интернета, вышек сотовой связи и центральных серверов.

Оно создано для тех, кто не может рассчитывать на нейтральность сети — журналистов, полевых операторов и всех, кто не хочет пропускать личную переписку через чужую инфраструктуру. Никаких аккаунтов, номеров телефона, каталогов — нечего изъять.

## Возможности

- **Сообщения без инфраструктуры** — работают даже при отключённом, урезанном или заблокированном интернете
- **Несколько способов связи** — Bluetooth LE для тех, кто рядом; Wi-Fi дома; LoRa-радио через [RNode](https://github.com/markqvist/RNode_Firmware) на расстоянии; TCP до любого узла Reticulum в мире
- **Приватность** — сквозное шифрование: без аккаунтов, без слежки, без центральных серверов
- **Постквантовая защита** — гибридное шифрование сообщений (X25519 + ML-KEM-768) с опечатыванием вложений
- **Шифрование базы данных** — переписка хранится на устройстве в зашифрованном виде
- **Блокировка приложения** — PIN-код на вход
- **Обмен геопозицией** — безопасно делитесь местоположением с выбранными контактами, просмотр на отдельной карте
- **Офлайн-карты** — векторные и растровые карты в формате MBTiles
- **Просмотр NomadNetwork** — доступ к страницам nomadnet через Reticulum
- **Стройте свою сеть** — ретранслируйте трафик других и расширяйте mesh-сеть
- **Ваша идентичность** — генерация идентификатора прямо на устройстве
- **Несколько идентичностей** — свободное переключение между ними
- **Экспорт и импорт** — резервное копирование ключей и перенос между устройствами. Импорт из других клиентов Reticulum, например [Sideband](https://github.com/markqvist/Sideband)
- **QR-код идентичности** — встроенный сканер и генератор
- **Пользовательские темы** — настройте внешний вид под себя

## Установка

Скачайте последний релиз со страницы [Releases](https://github.com/c0d3craft3r13/zamolxis/releases) и установите на Android-устройство. См. [SECURITY.md](./SECURITY.md) для инструкций по проверке APK — проверяйте перед установкой.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/c0d3craft3r13/zamolxis"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="60" alt="Получить в Obtainium"></a>

## О Reticulum

[Reticulum](https://github.com/markqvist/Reticulum) — сетевой стек, позволяющий устройствам обмениваться данными напрямую, образуя устойчивые mesh-сети. Он оптимизирован для каналов с низкой пропускной способностью и высокой задержкой и может работать практически через любую среду передачи. Zamolxis использует [LXMF](https://github.com/markqvist/LXMF) (Lightweight Extensible Message Format) для доставки сообщений и нативную Android-реализацию [ble-reticulum](https://github.com/torlando-tech/ble-reticulum) для обмена по BLE с Android- и Linux-устройствами.

Хотите узнать больше? Посетите [документацию Reticulum](https://reticulum.network/).

## Почему «Zamolxis»

Zamolxis — бог даков, связанный с бессмертием. Геродот рассказывает, что он удалился в подземное жилище на три года, пока его народ оплакивал его как мёртвого, — а затем вернулся. Подходящее имя для сети, которая затаивается и возвращается.

## Безопасность

Zamolxis хранит личную переписку и ключи идентичности. Сообщайте об уязвимостях приватно через [GitHub Security Advisories](https://github.com/c0d3craft3r13/zamolxis/security/advisories/new), никогда — в публичных issues. См. [SECURITY.md](./SECURITY.md) для модели угроз, включая то, что пока *не* покрыто.

## Поддержать проект

Zamolxis — свободный проект с открытым кодом. Если он вам полезен, можно поддержать разработку:

- **USDT (TRC-20, сеть Tron):** `TNPzvsfsdNC3XrxJPB2NMVh1nZSPcvZzxC`

<img src="./docs/images/donate-usdt-trc20.png" width="160" alt="QR-код для доната USDT TRC-20" />

## Лицензия

Zamolxis распространяется под Mozilla Public License 2.0 — см. [LICENSE.md](./LICENSE.md).

Это форк [Columba](https://github.com/torlando-tech/columba) от Columba Contributors, используется под той же лицензией. Апстрим-проект не связан с Zamolxis и не поддерживает его.
