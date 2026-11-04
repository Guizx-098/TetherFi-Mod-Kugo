## Kugo Hotspot 1.0.6 (experimental)

Baixe o arquivo `.apk` abaixo e instale no Android (permita fontes desconhecidas, se pedido).

### Novidades
- **Hotspot nativo sem o prefixo `DIRECT-` (experimental):** o botão "Ligar hotspot" do cartão do Shizuku agora chama o serviço de Wi-Fi do Android diretamente, em vez do comando que exigia root. O resultado, ou o erro exato, aparece no cartão.
- **Assinatura fixa:** as próximas versões instalam **por cima** da anterior. Se esta for a sua primeira instalação desde a mudança, **desinstale a versão antiga uma única vez**.
- **Aviso de atualização:** ao abrir o app, ele verifica se há uma versão nova aqui no GitHub e pergunta se você quer baixar. Também há o botão **Verificar atualizações** no topo da aba Configurações.
- **Diagnóstico do Shizuku:** botão **Copiar resultado**.

### Observações
- O hotspot nativo não usa o proxy do app. Bloqueio, limites e porta não valem para ele.
- O modo normal (Wi-Fi Direct com proxy) continua igual, com o nome começando por `DIRECT-`.

Baseado no TetherFuseNet, de pyamsoft (Apache 2.0). Projeto mantido por Guizx-098.
