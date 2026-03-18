---
applyTo: "src/main/scala/**/*.scala"
description: "Бэкенд-правила для Scala/ZIO: типобезопасность, ошибки, границы модулей, учебные комментарии"
---

# Core Backend

- Следуй текущим Scala 3 и ZIO паттернам проекта: case class, sealed trait, explicit match, ZLayer.
- Не используй Option.get и не скрывай ошибки без логирования причины.
- Держи границы модулей: network, protocol, service/filter, storage, command, api.
- Избегай блокировок в hot path и Netty event loop.
- При изменении бизнес-логики добавляй или обновляй тесты рядом с измененным модулем.
- Если встречаешь обучающие пометки QUESTION(U)/WHY(U)/CONFUSED(U), оставляй точечный ответ рядом тегом ANSWER(AI).
