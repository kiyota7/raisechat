# フロントエンド: Nodeでビルドし、nginxで配信する(/api・/uploads・/ws は、nginxがバックエンドへ中継する)
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# TLS終端のプロキシを信用する設定(本番用のcomposeが、ここにファイルを置く)。空なら何も信用しない
RUN mkdir -p /etc/nginx/proxy-trust
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
