<p align="center">
  <img src="docs/assets/icon.png" width="120" alt="Ícone do Kugo Hotspot">
</p>

<h1 align="center">Kugo Hotspot</h1>

<p align="center">
  Compartilhe a internet do seu Android com outros aparelhos, com lista de conexões em tempo real,
  interface em português e controle total sobre quem usa a sua rede.
</p>

<p align="center">
  <a href="https://github.com/Guizx-098/TetherFi-Mod-Kugo/releases/latest"><b>⬇ Baixar o APK</b></a>
  ·
  <a href="https://guizx-098.github.io/TetherFi-Mod-Kugo/">Página do app</a>
  ·
  <a href="https://github.com/Guizx-098/TetherFi-Mod-Kugo/issues">Reportar problema</a>
</p>

---

## O que é

O **Kugo Hotspot** transforma o seu celular Android em um hotspot Wi-Fi com proxy próprio. Ele
cria uma rede Wi-Fi Direct e repassa o tráfego dos aparelhos conectados pela conexão que o seu
celular já tem (Wi-Fi ou dados móveis). Não precisa de root.

- **Versão atual:** 1.0.0
- **Mantido por:** [Guizx-098](https://github.com/Guizx-098)
- **Licença:** Apache License 2.0 (veja [Créditos e licença](#créditos-e-licença))

## Funcionalidades

**Hotspot e rede**
- Hotspot via Wi-Fi Direct, com nome e senha configuráveis.
- Frequência de transmissão em 2,4 GHz, 5 GHz ou 6 GHz (quando o aparelho permite).
- Modo de tethering USB (RNDIS) para um aparelho por cabo.
- Escolha da rede preferida para o tráfego: ativa, Wi-Fi ou dados móveis.
- Código QR para conectar outros aparelhos rapidamente.

**Proxy**
- Proxy HTTP/HTTPS, simples e rápido (recomendado para a maioria dos usos).
- Proxy SOCKS4A/SOCKS5H, para casos técnicos e específicos.
- Ajuste do tempo limite de socket para quem precisa de mais desempenho ou estabilidade.

**Aparelhos conectados**
- Lista de conexões **atualizada em tempo real**: aparelhos que saem da rede deixam a lista
  sem precisar reiniciar o hotspot, e voltam sozinhos quando reconectam.
- **Apelidos** para cada aparelho, guardados no celular e restaurados na reconexão.
- Bloqueio individual de aparelhos.
- Limite de transferência e limite de banda por aparelho.

**Comportamento e economia**
- Modo "Sempre ativo", wakelock, "Manter a tela ligada" e desligamento automático sem aparelhos.
- Atalho no Quick Settings (tile) e atalho no ícone do app para ligar e desligar o hotspot.
- Notificação com o status e o número de aparelhos conectados.

**Idioma**
- Interface completa em **português do Brasil**, além do inglês.

## Como instalar

1. Abra a página de [Releases](https://github.com/Guizx-098/TetherFi-Mod-Kugo/releases/latest).
2. Baixe o arquivo `.apk` mais recente.
3. No Android, permita a instalação de apps de fontes desconhecidas quando for solicitado.
4. Abra o app, conceda as permissões pedidas e toque em **Iniciar Hotspot**.

> Requisitos: Android com Wi-Fi Direct. O nome da rede criada pelo sistema sempre começa com
> `DIRECT-xx-`, uma exigência do Android para redes Wi-Fi Direct.

## Compilar o projeto

O APK é compilado automaticamente pelo GitHub Actions:

- A cada push na branch `main`, o workflow **Build TetherFi APK** gera um APK de teste.
- Ao publicar uma tag `v*` (por exemplo `v1.0.0`), o workflow **Publicar APK** compila e anexa o
  APK à Release.

Para compilar localmente: Java 21, Android SDK e `./gradlew :app:assembleDebug`.

## Privacidade

O app não exige conta nem login. Veja a [Política de Privacidade](PRIVACY_POLICY.md).

## Créditos e licença

O Kugo Hotspot é um projeto derivado do **TetherFuseNet** (também conhecido como TetherFi),
criado por **pyamsoft** e distribuído sob a licença **Apache 2.0**. As modificações deste
repositório (tradução para português, lista de conexões em tempo real, nova identidade visual e
ajustes) são de responsabilidade de Guizx-098.

Em respeito à licença, o arquivo [LICENSE](LICENSE) e os avisos de direitos autorais do código
original foram mantidos, e o arquivo [NOTICE](NOTICE) registra a origem e as alterações.
Este projeto não é afiliado nem endossado por pyamsoft.
