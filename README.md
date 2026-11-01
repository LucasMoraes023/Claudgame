# CAIPORAH CLOUD GAMER

Painel web (FastAPI + HTML) para acompanhar sessões de jogos em um Android remoto, com foco em AFK e custo zero.

**Estado real do projeto (v0.1):** o painel, a API, o login, as sessões, o histórico e os testes funcionam. Só dois provedores existem: **DEMO** (simulado, nunca é AFK real) e **ADB** (aparelho Android real que você já possui). Não existe integração com nuvem Android, porque nenhum serviço gratuito foi verificado como adequado. Nada foi inventado.

## Rodar (sem computador)
Pelo Claude Code com este repositório conectado, peça: "instale as dependências e rode os testes". Ou use o Termux no Android:
```
pip install -r requirements.txt
ADMIN_PASSWORD=troque-esta-senha uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Abra `http://localhost:8000`. Se `ADMIN_PASSWORD` ficar vazio, uma senha aleatória aparece no log de inicialização.

## Testar sem Android
Em Dispositivos, cadastre um dispositivo **DEMO** e conecte pelo Dashboard. Tudo aparece marcado como DEMO.

## Usar um Android real (ADB)
1. No aparelho: ative Opções do desenvolvedor e depuração por Wi-Fi.
2. O servidor da API precisa ter o `adb` instalado e alcançar o aparelho por rede privada (ex.: uma VPN como o Tailscale, que tem plano gratuito; confirme as condições atuais). Nunca exponha a porta ADB na internet.
3. Cadastre o dispositivo com o endereço `ip:porta`. O painel só mostra online se o `adb get-state` responder.
4. O jogo é verificado com `pidof <pacote>`. O app informa se o jogo fechou, mas não o reabre.

## Implantação gratuita
Hospedar só a API/site em um plano gratuito de PaaS (ex.: Render, Koyeb, Fly.io) é possível, mas **confira os limites atuais**: planos grátis costumam hibernar e apagar o disco, o que apaga o SQLite. Comando de início: `uvicorn app.main:app --host 0.0.0.0 --port $PORT`. A hospedagem em nuvem não alcança um aparelho em rede doméstica sem VPN.

## Variáveis de ambiente
`ADMIN_USER`, `ADMIN_PASSWORD`, `CAIPORAH_DB` (veja `.env.example`). Não commite o `.env`.

## Segurança
Senhas com PBKDF2; tokens guardados só como hash e válidos por 24 h; bloqueio após 5 logins falhos; validação de entrada; nenhum segredo no frontend (o token de sessão fica no `sessionStorage` do navegador). Use HTTPS na implantação.

## Limitações
- Nenhuma nuvem Android gratuita e permanente foi integrada. GitHub Actions, Codespaces e Colab não servem como servidor AFK.
- Se o aparelho ou servidor Android desligar, o jogo para e a sessão é marcada como interrompida no próximo "Atualizar status".
- A verificação é por clique ou ao iniciar sessão; não há monitoramento automático em segundo plano.
- Não há controle remoto de tela, instalação de APK nem reconexão automática.
- O projeto não contorna anti-cheat nem CAPTCHA. Confirme que AFK é permitido no seu jogo.

## Próximas etapas
Monitoramento periódico em segundo plano, abrir o jogo via ADB (`monkey`), controle de tela (scrcpy), cadastro de novos usuários, integração real com um provedor de nuvem após validar seus limites.

## Testes
`python -m pytest -q`
