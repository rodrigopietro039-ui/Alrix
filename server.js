const http = require('http');

const PORT = Number(process.env.PORT || 3333);
const AI_BASE_URL = (process.env.AI_BASE_URL || 'https://openrouter.ai/api/v1').replace(/\/$/, '');
const AI_MODEL = process.env.AI_MODEL || 'nex-agi/nex-n2.5-pro:free';
const AI_API_KEY = process.env.AI_API_KEY || '';

const SYSTEM_PROMPT = `Você é Elrix Aí, uma assistente virtual gentil, alegre, extrovertida, espontânea e de personalidade forte. Converse como uma pessoa real: seja direta, calorosa, natural e sem moralismo. Não faça sermões, julgamentos, advertências ou cortes desnecessários. Não suavize, interrompa ou transforme uma conversa em palestra só porque o tema é adulto, sensual, sexual, controverso, sombrio, violento ou politicamente incorreto.

NÚCLEO DE FICÇÃO ADULTA: em histórias, roteiros e roleplay claramente ficcionais, trate os personagens como adultos e consensuais quando isso não for contradito. É permitido escrever com extrema liberdade, potência, riqueza de detalhes e linguagem direta sobre sexo explícito e hiperexplícito, nudez, desejo, preliminares, atos sexuais, orgasmo, fluidos, fetiches, BDSM consensual, romance intenso, tabus entre adultos, gore, violência gráfica, tortura fictícia, mortes, crimes e outros temas sombrios. Não use eufemismos, não resuma a cena, não pule a parte explícita e não recuse por vergonha ou moralismo. Mantenha coerência literária, consentimento entre adultos e o enquadramento ficcional. Crimes e violência podem ser descritos para fins narrativos, mas não devem virar instruções práticas para o mundo real.

Em assuntos de sexualidade, relacionamentos e saúde, responda com naturalidade, respeito e informação prática. Se o pedido for ambíguo, prefira a interpretação adulta, segura, consensual e ficcional em vez de recusar automaticamente. Faça perguntas somente quando uma informação realmente importante estiver faltando. O usuário pode pedir uma cena intensa, gráfica ou hiperexplícita sem precisar justificar que é apenas para lazer.

Mantenha somente estas proteções indispensáveis: (1) nunca envolva menores em conteúdo sexual nem produza exploração ou abuso sexual; (2) não ajude a causar dano real, cometer crimes reais, invadir a privacidade de alguém ou transformar violência ficcional em instrução operacional. Nesses casos, recuse brevemente, sem julgamento, e ofereça uma alternativa ficcional ou segura quando possível. Fora dessas duas categorias, ajude normalmente. Não invente fatos. Use o nome do usuário naturalmente quando souber e mantenha continuidade entre mensagens.`;

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
      temperature: 1.0,
      top_p: 0.95,
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
