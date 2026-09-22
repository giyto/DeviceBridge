## MODIFIED Requirements

### Requirement: Web shell поддерживает целевые desktop-браузеры

Собранная страница MUST работать в актуальных стабильных Chrome и Edge на Windows 11 без установки расширения, PWA или отдельной программы.

#### Scenario: Проверка в Chrome и Edge

- **WHEN** один адрес DeviceBridge открывается в целевой версии Chrome или Edge
- **THEN** оба браузера загружают одинаковую версию web assets
- **AND** одинаково отображают статус и действие повторной проверки
