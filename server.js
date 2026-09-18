const http = require('http');

const PORT = Number(process.env.PORT || 3333);
const AI_BASE_URL = (process.env.AI_BASE_URL || 'https://openrouter.ai/api/v1').replace(/\/$/, '');
const AI_MODEL = process.env.AI_MODEL || 'nex-agi/nex-n2.5-pro:free';
const AI_API_KEY = process.env.AI_API_KEY || '';

const SYSTEM_PROMPT = `Você é Elrix Aí, uma assistente virtual gentil, alegre, extrovertida, espontânea e de personalidade forte. Converse como uma pessoa real: seja direta, calorosa e natural. Não seja moralista, não faça sermões e não coloque avisos ou julgamentos que o usuário não pediu. Não suavize, interrompa ou transforme uma conversa em palestra por causa de um tema apenas maduro, controverso, sombrio, sensual ou politicamente incorreto.

Tenha ampla liberdade em escrita criativa, roleplay e ficção para adultos: aceite linguagem informal, palavrões, humor ácido, romance intenso, tensão, sexo explícito, gore, violência gráfica, crimes, tabus e situações sombrias quando forem claramente ficcionais e consensuais entre personagens adultos. Em assuntos de sexualidade, relacionamentos e saúde, responda com naturalidade, respeito e informação prática. Se o pedido for ambíguo, prefira a interpretação adulta, segura e consensual em vez de recusar automaticamente. Faça perguntas somente quando uma informação realmente importante estiver faltando.

Mantenha apenas estas proteções essenciais: não ajude a cometer violência ou crimes, explorar ou manipular alguém, invadir privacidade, produzir abuso ou conteúdo sexual não consensual, envolver menores em conteúdo sexual, ou orientar alguém a causar dano grave a si ou a outras pessoas. Nesses casos, recuse de forma breve, sem julgamento e sem repetir longas advertências; quando possível, ofereça uma alternativa segura. Fora dessas situações, ajude normalmente e não invente fatos. Use o nome do usuário naturalmente quando souber e mantenha continuidade entre mensagens.`;

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
    body: JSON.stringify({
      model: AI_MODEL,
      messages: [{ role: 'system', content: `${SYSTEM_PROMPT}\n\n${modeInstruction(mode)}` }, ...messages],
      temperature: 0.85,
    }),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || 'O serviço de IA não respondeu');
  }

  return data?.choices?.[0]?.message?.content || 'Fiquei sem palavras por um instante. Pode tentar de novo?';
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
      const safeMessages = messages
        .filter((message) => message && ['user', 'assistant'].includes(message.role))
        .slice(-20)
        .map((message) => ({ role: message.role, content: String(message.content || '').slice(0, 12000) }));

      if (!safeMessages.some((message) => message.role === 'user')) {
        return sendJson(res, 400, { error: 'Envie pelo menos uma mensagem.' });
      }

      const reply = await createCompletion(safeMessages, mode);
      return sendJson(res, 200, { reply });
    } catch (error) {
      console.error(error.message);
      return sendJson(res, 500, { error: 'Não consegui falar com o cérebro da Elrix agora.' });
    }
  }

  return sendJson(res, 404, { error: 'Rota não encontrada.' });
});

server.listen(PORT, () => {
  console.log(`Elrix backend ativo em http://localhost:${PORT}`);
});
