---
applyTo: "src/test/scala/**/*.scala"
description: "Тестовые правила: структура ZIO Test, покрытие критичных веток, учебные пояснения"
---

# Testing Department

- Используй существующий стиль ZIOSpecDefault и структуру suite/test.
- Для изменений в parser/encoder/network добавляй edge/error тесты, а не только happy path.
- Для flaky-рисков явно фиксируй предпосылки теста и ожидаемый результат.
- При наличии QUESTION(U) в тесте добавляй ANSWER(AI) с объяснением, какую инварианту фиксирует тест.
