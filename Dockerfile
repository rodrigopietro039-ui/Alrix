FROM node:20-alpine
WORKDIR /app
COPY package.json ./
COPY server.js ./
ENV PORT=3333
EXPOSE 3333
CMD ["node", "server.js"]
