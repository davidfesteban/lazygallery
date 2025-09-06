FROM node:20-alpine

WORKDIR /app

# Copy package manifest files first to leverage Docker cache
COPY package*.json ./

# Install all dependencies (prod + optional dev if needed)
RUN npm install --omit=dev

# Copy application source
COPY public ./public
COPY server.js ./

# Ensure uploads directory exists
RUN mkdir -p /app/uploads

EXPOSE 8080
CMD ["node", "server.js"]
