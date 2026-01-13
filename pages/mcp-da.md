# Brug DanNet med AI-værktøjer (beta)
DanNet kan forbindes direkte til AI-assistenter som [Claude][Claude], [ChatGPT][ChatGPT] og andre værktøjer, der understøtter [Model Context Protocol][MCP] (MCP). Dette giver dig mulighed for at forespørge danske ordbetydninger, semantiske relationer og sproglige data gennem naturlig samtale.

> **Bemærk:** Denne funktion er i øjeblikket i beta. MCP-standarden er stadig under udvikling, og ikke alle AI-værktøjer understøtter den endnu.

## Hvad er MCP?
[Model Context Protocol][MCP] er en åben standard, der gør det muligt for AI-assistenter at forbinde til eksterne datakilder. Tænk på det som et plugin-system til AI-værktøjer. Når DanNet er forbundet via MCP, kan AI'en slå information op direkte i vores database i stedet for udelukkende at stole på sine træningsdata.

## Sådan forbinder du DanNet til dit AI-værktøj
For at forbinde DanNet skal du tilføje det som en "connector" eller "MCP-server" i dit AI-værktøjs indstillinger. Detaljerne varierer fra værktøj til værktøj, men du skal typisk bruge:

- **MCP-server-URL:** `https://wordnet.dk/mcp`
- **Navn:** DanNet (eller et andet navn efter eget valg)

### Eksempel: Claude Desktop
I [Claude Desktop][Claude Desktop] skal du gå til `Settings > Connectors > Browse Connectors` og klikke på "add a custom one". Indtast "DanNet" som navn og `https://wordnet.dk/mcp` som server-URL.

![Claude Desktop opsætning](/images/claude_desktop_custom_connector.png)

### Eksempel: Claude.ai
På [claude.ai][Claude.ai] skal du gå til `Settings > Connectors` og klikke på "Add connector". Indtast "DanNet" som navn og `https://wordnet.dk/mcp` som server-URL.

## Hvad kan du spørge om?
Når forbindelsen er oprettet, kan du stille din AI-assistent spørgsmål som:

- "Hvad er nogle synonymer for 'glad'?"
- "Hvordan er 'hund' relateret til andre dyrebegreber i DanNet?"
- "Hvilke ord er hyponymer af 'møbel'?"

AI'en vil forespørge DanNet direkte og give dig svar baseret på vores leksikalske database.

## Tekniske detaljer
DanNet er registreret i MCP-serverregistret som `io.github.kuhumcst/dannet`. For udviklere, der er interesserede i implementeringen, se [MCP-serverens kildekode][MCP source] på Github.

[Claude]: https://www.anthropic.com/claude "Claude fra Anthropic"
[ChatGPT]: https://openai.com/chatgpt "ChatGPT fra OpenAI"
[MCP]: https://modelcontextprotocol.io/ "Model Context Protocol"
[Claude Desktop]: https://claude.ai/download "Download Claude Desktop"
[Claude.ai]: https://claude.ai/ "Claude.ai"
[MCP source]: https://github.com/kuhumcst/DanNet/tree/master/mcp "DanNet MCP-server kildekode"
