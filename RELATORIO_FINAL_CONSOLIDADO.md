# RELATÓRIO FINAL CONSOLIDADO — NEURALBRIDGE COMPANION v0.1.1

**Fork:** rosestolatoti/NeuralBridge_mcp ← dondetir/NeuralBridge_mcp
**Dispositivo:** Motorola Moto G84 5G | Android 15 (API 35)
**Estado do fork:** main atualizado, build workflow funcional (GitHub Actions)
**Data:** 29/07/2026

---

## FASE 0 — STATUS DO FORK

| Item | Status |
|---|---|
| Fork existe com remote apontado para rosestolatoti | ✅ |
| Build workflow (.github/workflows/build-apk.yml) funcional | ✅ |
| Trigger: workflow_dispatch (debug/release), SDK 34, NDK 27, CMake 3.22.1 | ✅ |
| Fixes anteriores de MediaProjection aplicados | ✅ |
| **canTakeScreenshot | FOREGROUND_SERVICE_TYPE | bateria** | **❌ AINDA NÃO** |

### Commits relevantes do fork

```
f9553dd Fix: remove refs obsoletas hasConsentResult/consumeConsentResult
c771f00 Fix: MediaProjection agora cria projeção imediatamente no onActivityResult
29ad35e Fix definitivo: gera gradle-wrapper.jar no workflow
635f478 Fix: replace broken ADB screenshot fallback with AccessibilityService.takeScreenshot()
```

---

## FASE 1 — DIAGNÓSTICO CONFIRMADO

### BUG #1.1 — canTakeScreenshot="true" AUSENTE (novo, decisivo)

| Campo | Valor |
|---|---|
| **Arquivo** | `res/xml/accessibility_service_config.xml` |
| **Atributo faltando** | `android:canTakeScreenshot="true"` |
| **Afeta** | `AccessibilityService.takeScreenshot()` — o fallback inteiro |
| **Erro** | `SecurityException: "Services don't have the capability of taking the screenshot"` |
| **Fonte** | Android docs — `CAPABILITY_CAN_TAKE_SCREENSHOT` (API 30+) |
| **Confirmado** | Código analisado: xml NÃO tem o atributo |

**Atributos existentes vs faltando:**

```
✅ android:canRetrieveWindowContent="true"
✅ android:canPerformGestures="true"
✅ android:canRequestFilterKeyEvents="true"
❌ android:canTakeScreenshot="true"  ← FALTANDO
```

---

### BUG #1.2 — FOREGROUND_SERVICE_TYPE incompleto

| Campo | Valor |
|---|---|
| **Arquivo** | `NeuralBridgeAccessibilityService.kt` (linha 181) |
| **startForeground() usa só** | `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| **Falta** | `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` |
| **Afeta** | `MediaProjection.createVirtualDisplay()` — caminho rápido |
| **Fonte** | Android docs — fg-service-types (mediaProjection) |
| **Observação** | Manifest já declara `specialUse\|mediaProjection` ✅ |

---

### BUG #1.3 — TOKEN MEDIAPROJECTION É DE USO ÚNICO

A partir do **Android 14**, cada MediaProjection só pode ser usado **UMA VEZ**:

- `createVirtualDisplay()` não pode ser chamado 2x na mesma instância
- `Intent` de `createScreenCaptureIntent()` não pode ser reutilizado

O código atual (`ScreenshotConsentActivity.kt`) **armazena uma projeção pré-criada** (`pendingProjection`) para consumo posterior. Se o design for "autoriza uma vez, usa sempre", ele precisa mudar: ou mantém sessão contínua (sem destruir VirtualDisplay entre capturas), ou pede consentimento novo a cada sessão.

**Fonte:** Android 14 behavior changes (documentação oficial)
**Impacto:** Pode só aparecer na 2ª captura em diante — uma captura funcionando NÃO significa que o bug está resolvido.

---

### TRIPLO BLOQUEIO — JUNTANDO OS 3 BUGS

```
Caminho 1: MediaProjection → createVirtualDisplay()
  └── Bloqueado por: BUG #1.2 (foreground service type)

Caminho 2: AccessibilityService.takeScreenshot() (fallback)
  └── Bloqueado por: BUG #1.1 (capability canTakeScreenshot)

Ambos os caminhos → BUG #1.3 (token único) se reutilizar instância
```

NENHUM screenshot funciona = "SLOW" permanente no status.

---

### BUG #2 — Bateria: Permissão FALTANDO

| Campo | Valor |
|---|---|
| **Intent usada** | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **Método de verificação** | `PowerManager.isIgnoringBatteryOptimizations()` |
| **Permissão** | **❌ NÃO DECLARADA no AndroidManifest.xml** |
| **Fabricante** | Motorola personalizou/removeu tela original (comportamento conhecido) |
| **Sintoma** | Botão GRANT não abre nada / redireciona para Battery Saver |
| **Testado** | `am start ... REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → Battery Saver |

---

### BUG #3 — Race condition enable/disable do MCP

| Campo | Valor |
|---|---|
| **Causa** | `disable()` faz `stop()` assíncrono + seta `mcpHttpServer = null` |
| **Efeito** | `enable()` cria novo servidor na mesma porta 7474 — porta ocupada |
| **Sintoma** | "Ao clicar ENABLE, o servidor MCP desliga" |
| **Solução** | `disable()` aguardar stop() completar OU `enable()` verificar se já tem server rodando |

---

## FASE 2 — TESTES AO VIVO (executados neste dispositivo)

```
MCP Server: http://192.168.0.122:7474/mcp

✅ android_get_ui_tree       → 54 nós (Settings), 4 nós (Termux)
✅ android_get_device_info   → Motorola/G84/Android 15/SDK 35/1080x2160
✅ tools/list                → 32 ferramentas disponíveis
❌ android_screenshot        → FALHOU (duplo: MediaProjection + fallback)
❌ am start IGNORE_BATTERY   → redireciona para Battery Saver
```

---

## FASE 3 — PLANO DE EXECUÇÃO FINAL

### ✅ PRONTO PRA APLICAR AGORA (FASE 1 — screenshot)

1. **`accessibility_service_config.xml`** — adicionar `android:canTakeScreenshot="true"`
   → resolve o fallback

2. **`NeuralBridgeAccessibilityService.kt` linha 181** — incluir `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
   ```kotlin
   val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                 ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
   startForeground(NOTIFICATION_ID, notification, fgsType)
   ```
   → resolve o caminho rápido

3. **Auditar fluxo de consentimento** — verificar se token não é reutilizado
   - Se sim: manter sessão contínua ou forçar novo consentimento a cada sessão

4. **AndroidManifest.xml** — adicionar:
   ```xml
   <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
   ```

### ⏸️ SEGUNDA RODADA (depois de screenshot estável)

5. Bug #3 — race condition enable/disable (usar runBlocking no stop)
6. Bug #4 — mostrar erro na UI em vez de só logar
7. Bug #5 — compileSdk = 35 (opcional)

### Como buildar

**Via GitHub Actions (recomendado):**
```bash
gh workflow run build-apk.yml -f build_mode=debug
```

**Local (se tiver SDK):**
```bash
cd android && ./gradlew assembleDebug
```

---

## CRITÉRIO DE SUCESSO

```
✅ 3+ capturas de tela SEGUIDAS sem erro
✅ Funciona após REBOOT + reconceder permissões do zero
✅ Funciona após fechar e reabrir app (sem reboot)
✅ Nenhum SecurityException no logcat sobre MediaProjection
✅ Fallback AccessibilityService também funciona isoladamente
```

Só quando esses 5 critérios passarem, o bug está resolvido de verdade.
