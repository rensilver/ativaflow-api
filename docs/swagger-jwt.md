# Usar JWT no Swagger UI

Execute os comandos na raiz do projeto, com JDK 21. Inicie o ambiente conforme o
[Início rápido](../README.md#início-rápido):

```bash
./scripts/dev-up.sh
```

## Gerar um token com as chaves da API local

Reutilize o diretório registrado pelo script de inicialização. O utilitário
existente gera um novo token com as mesmas chaves, válido por cinco minutos:

```bash
export JWT_DEMO_DIR="$(cat .env.jwt-dir)"
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
```

O token identifica `demo-consumer` e inclui `requests/read` e `requests/write`.
Para copiar somente o JWT para o Swagger, exiba-o no seu terminal local:

```bash
sed -n 's/^Authorization: Bearer //p' "$JWT_DEMO_DIR/authorization.header"
```

Se iniciou a API manualmente pelo Maven, reutilize o `JWT_DEMO_DIR` utilizado na
inicialização, conforme o [guia de JWT local](local-jwt.md). Gerar outro par de
chaves com `create` não produz tokens aceitos pela API que já está em execução.

## Autorizar e executar

1. Abra o [Swagger UI](http://localhost:8080/swagger-ui.html). Ajuste a porta se
   alterou `SERVER_PORT`.
2. Clique em **Authorize** e, no esquema **bearerAuth**, cole somente o JWT, sem
   `Bearer ` nem `Authorization: `.
3. Clique em **Authorize** e depois em **Close**.
4. Expanda um endpoint de `/api/v1/requests`, clique em **Try it out** e **Execute**.
   A interface envia automaticamente `Authorization: Bearer <JWT>`.

O POST exige `requests/write`; as consultas GET exigem `requests/read`. Para criar
uma solicitação, use um `externalId` novo; para consultar por ID, use o `id`
retornado pela criação, com o mesmo consumidor.

Um `401` indica token ausente, inválido ou expirado. Renove com o comando `token`
acima e substitua o JWT em **Authorize** (use **Logout** antes, se necessário).
Um `403` indica falta do escopo exigido em um token válido.

A interface e `/v3/api-docs/**` são acessíveis sem autenticação no ambiente local;
os endpoints de negócio continuam protegidos pelo Resource Server. O JWT é uma
credencial: não o compartilhe nem o salve no repositório. Ao terminar, clique em
**Authorize → Logout**.
