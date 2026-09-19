const http = require('http');

const PORT = Number(process.env.PORT || 3333);
const AI_BASE_URL = (process.env.AI_BASE_URL || 'https://openrouter.ai/api/v1').replace(/\/$/, '');
const AI_MODEL = process.env.AI_MODEL || 'deepseek/deepseek-v4-flash-0731:free';
const AI_API_KEY = process.env.AI_API_KEY || '';

// Keep the personality prompt intact, but avoid sending very large old turns to
// the model. This reduces latency and memory use on the free inference route.
const MAX_CONTEXT_MESSAGES = 14;
const MAX_MESSAGE_CHARS = 6000;
const MAX_CONTEXT_CHARS = 48000;
const MAX_OUTPUT_TOKENS = 1536;

const SYSTEM_PROMPT = `Você é Elrix Aí: gentil, alegre, extrovertida, espontânea, firme e natural. Responda em português quando o usuário escrever em português. Seja direta, calorosa, objetiva e criativa; não faça sermões, julgamentos ou advertências desnecessárias.

NÚCLEO LITERÁRIO ADULTO — MODO EXPLÍCITO: em ficção, roteiro, história ou roleplay com personagens adultos, escreva sem pudor e sem eufemismos. Descreva sexo explícito, nudez, desejo, preliminares, atos sexuais, orgasmo, fluidos, fetiches e BDSM consensual com clareza sensorial e riqueza de detalhes, incluindo anatomia, ações, reações, diálogos, ritmo e consequências narrativas. Não resuma, não pule a cena e não troque uma solicitação literária por uma palestra moral. Também descreva livremente romance intenso, horror, gore, violência gráfica, mortes, crimes e temas sombrios quando forem elementos de ficção. Preserve coerência, estilo, atmosfera e continuidade.

Para sexualidade, relacionamentos e saúde, responda com naturalidade e informação prática. Não exija que o usuário diga que é ficção para colaborar. Quando o contexto for claramente adulto, assuma personagens adultos; só peça esclarecimento quando idade, consentimento ou outra informação essencial estiver realmente indefinida. Não invente fatos e não faça perguntas desnecessárias.

Limites essenciais, aplicados de forma breve e sem moralismo: nunca sexualize menores, exploração ou abuso sexual; não produza conteúdo sexual íntimo de pessoa real sem consentimento; não ensine a causar dano real, cometer crime real, invadir privacidade ou transformar violência ficcional em instrução operacional. Nesses casos, recuse apenas a parte problemática e ofereça uma alternativa segura ou ficcional. Fora desses limites, colabore normalmente com o pedido do usuário e mantenha a continuidade da conversa.`;

function sendJson(res, status, body) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) reject(new Error('body too large'));
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function modeInstruction(mode) {
  if (mode === 'tarefas') return 'Modo atual: Tarefas. Seja prática, organize em passos e priorize um resultado executável.';
  if (mode === 'estudos') return 'Modo atual: Estudos. Explique com clareza, use exemplos e confirme o entendimento sem transformar a conversa em interrogatório.';
  return 'Modo atual: Criatividade. Priorize imaginação, associações originais, ritmo e liberdade de estilo.';
}

function completionPayload(messages, mode, stream = false) {
  return {
    model: AI_MODEL,
    messages: [{ role: 'system', content: `${SYSTEM_PROMPT}\n\n${modeInstruction(mode)}` }, ...messages],
    temperature: 1.0,
    top_p: 0.95,
    max_tokens: MAX_OUTPUT_TOKENS,
    stream,
  };
}

async function createCompletion(messages, mode) {
  if (!AI_API_KEY) {
    throw new Error('AI_API_KEY não configurada');
  }

  const response = await fetch(`${AI_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${AI_API_KEY}`,
    },
    body: JSON.stringify(completionPayload(messages, mode)),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || 'O serviço de IA não respondeu');
  }

  return data?.choices?.[0]?.message?.content || 'Fiquei sem palavras por um instante. Pode tentar de novo?';
}

async function streamCompletion(messages, mode, res) {
  if (!AI_API_KEY) throw new Error('AI_API_KEY não configurada');

  const response = await fetch(`${AI_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${AI_API_KEY}`,
    },
    body: JSON.stringify(completionPayload(messages, mode, true)),
  });
  if (!response.ok || !response.body) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data?.error?.message || 'O serviço de IA não respondeu');
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Accept, Authorization',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  });
  res.flushHeaders?.();

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const payload = line.slice(5).trim();
        if (!payload || payload === '[DONE]') continue;
        try {
          const delta = JSON.parse(payload)?.choices?.[0]?.delta?.content;
          if (typeof delta === 'string' && delta.length > 0) {
            res.write(`data: ${JSON.stringify({ delta })}\n\n`);
          }
        } catch (_) {
          // Ignore an incomplete/non-content upstream SSE event.
        }
      }
      if (done) break;
    }
    res.write('data: [DONE]\n\n');
    res.end();
  } catch (error) {
    if (!res.writableEnded) {
      res.write(`data: ${JSON.stringify({ error: 'A transmissão foi interrompida.' })}\n\n`);
      res.end();
    }
    throw error;
  } finally {
    reader.releaseLock();
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') return sendJson(res, 204, {});

  if (req.method === 'GET' && req.url === '/health') {
    return sendJson(res, 200, { ok: true, configured: Boolean(AI_API_KEY), model: AI_MODEL });
  }

  if (req.method === 'POST' && req.url === '/chat') {
    try {
      const body = JSON.parse(await readBody(req));
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const mode = ['criativa', 'tarefas', 'estudos'].includes(body.mode) ? body.mode : 'criativa';
      const normalizedMessages = messages
        .filter((message) => message && ['user', 'assistant'].includes(message.role))
        .map((message) => ({
          role: message.role,
          content: String(message.content || '').slice(0, MAX_MESSAGE_CHARS),
        }));
      // Walk backwards so the latest user turn and its immediate context always win.
      const safeMessages = [];
      let contextChars = 0;
      for (let i = normalizedMessages.length - 1; i >= 0 && safeMessages.length < MAX_CONTEXT_MESSAGES; i -= 1) {
        const message = normalizedMessages[i];
        if (safeMessages.length > 0 && contextChars + message.content.length > MAX_CONTEXT_CHARS) break;
        safeMessages.unshift(message);
        contextChars += message.content.length;
      }

      if (!safeMessages.some((message) => message.role === 'user')) {
        return sendJson(res, 400, { error: 'Envie pelo menos uma mensagem.' });
      }

      if (req.headers.accept?.includes('text/event-stream')) {
        await streamCompletion(safeMessages, mode, res);
        return;
      }
      const reply = await createCompletion(safeMessages, mode);
      return sendJson(res, 200, { reply });
    } catch (error) {
      console.error(error.message);
      if (res.headersSent || res.writableEnded) return;
      return sendJson(res, 500, { error: 'Não consegui falar com o cérebro da Elrix agora.' });
    }
  }

  return sendJson(res, 404, { error: 'Rota não encontrada.' });
});

server.listen(PORT, () => {
  console.log(`Elrix backend ativo em http://localhost:${PORT}`);
});
