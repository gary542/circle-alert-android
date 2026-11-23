const https = require("node:https");
const http = require("node:http");
const fs = require("node:fs");

const [certificate, key] = process.argv.slice(2);
const server = https.createServer({cert: fs.readFileSync(certificate), key: fs.readFileSync(key)}, (request, response) => {
    const upstream = http.request({
        hostname: "127.0.0.1", port: 41893, path: request.url,
        method: request.method, headers: request.headers, agent: false
    }, incoming => {
        response.writeHead(incoming.statusCode, incoming.headers);
        incoming.pipe(response);
    });
    upstream.on("error", () => { response.writeHead(502); response.end(); });
    request.pipe(upstream);
});
server.listen(41892, "127.0.0.1");
