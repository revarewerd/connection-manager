---
applyTo: "src/main/scala/com/wayrecall/tracker/network/**/*.scala"
description: "Сетевой hot path: не блокировать I/O, сохранять порядок обработки, наблюдаемость"
---

# Network Department

- Не блокируй Netty event loop.
- Сохраняй текущие async/fork и порядок обработки пакетов на соединение.
- Избегай лишних аллокаций и лишних внешних вызовов в hot path.
- При изменениях ConnectionHandler/ConnectionRegistry/TcpServer проверяй регрессии по логике reconnect, idle timeout, cleanup.
- Для учебных вопросов владельца в этом слое отвечай с привязкой к lifecycle соединения: channelActive -> channelRead -> channelInactive.
