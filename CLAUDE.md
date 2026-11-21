# Lazarus — guía para Claude Code

App Android de despertador: **la alarma no se apaga hasta que el usuario lee en voz alta el Evangelio del día**
(liturgia católica, texto de *El Libro del Pueblo de Dios*, la traducción de la CEA), en español.
Responder siempre en español rioplatense. A Samir (el dueño) le gusta decidir las funcionalidades: ante
opciones reales, proponer y preguntar en vez de elegir por él.

## Decisiones de producto (ya tomadas)
- Varias alarmas, con días de la semana por alarma (o una sola vez).
- Volumen progresivo, tamaño de letra configurable con muestra.
- **Sin posponer.** Botones de volumen y "atrás" no apagan; salida de emergencia = mantener apretado 8 s.
- Reconocimiento de voz contra el texto del día; se apaga al llegar al % de acierto fijado (80 % por defecto).
  Si hay silencio (30 s por defecto) o no llega al %, vuelve a sonar.
- Foreground service para sonar y mantener la pantalla.
- Extras ya implementados: temas (sistema/claro/oscuro/AMOLED), modo ecuménico, color litúrgico, racha,
  sonidos propios, aviso de horas de sueño / pregunta nocturna (22 h), "Versículo del día" como fondo de
  pantalla de bloqueo, actualización desde la app vía Releases de GitHub.

## Arquitectura (`app/src/main/java/ar/despertador/evangelio/`, ~4200 líneas Kotlin, sin dependencias externas ni AndroidX)
- `AlarmScheduler` — `AlarmManager.setAlarmClock` (exacta, robusta a Doze); `AlarmReceiver` dispara,
  `BootReceiver` reprograma tras reinicio/actualización/cambio de permiso de alarmas exactas.
- `AlarmService` — foreground service (`mediaPlayback`), WakeLock parcial, rampa de volumen, vibración.
- `AlarmActivity` — pantalla de la alarma (showWhenLocked/turnScreenOn), `SpeechRecognizer`, lógica de
  umbral/silencio/reintento y salida de emergencia.
- `Matcher` — alinea lo oído con el texto palabra por palabra; tolera acentos, fonética, palabras salteadas,
  y más aún los nombres propios.
- `Gospel` (`GospelRepo`) — fuente: `feed.evangelizo.org/v2/reader.php` (evangeliodeldia.org). Cachea hoy + 6
  días en SharedPreferences y poda lo viejo; `GospelSyncJob` sincroniza una vez por día. Si no hay
  nada: el Evangelio guardado más cercano; recién después el texto de reserva (Jn 3,16-17, Reina-Valera 1909),
  ambos marcados `offline`. `FeedHealth` cuenta las fallas de sincronización: cartel en la pestaña Alarmas y
  notificación a la segunda falla seguida. Evangelizo es la única fuente: no hay segunda.
- `Liturgy` (color litúrgico por fecha), `Streak`, `Prefs`, `Alarms` (modelo + `AlarmStore`), `Sounds`.
- `Reminders` (`Night`, `Awake`), `LockVerse` / `VersePicker` — versículo diario y su elección.
- `MainActivity` (~1050 líneas), `Ui.kt`, `Permissions.kt`, `Intro.kt` — UI armada por código, sin XML de layouts ni Compose.
  Partir más `MainActivity` queda pendiente (Evangelio, ajustes, versículo de bloqueo, actualizaciones).
- `Updater` — consulta `skyd0pe/lazarus-app` releases/latest e instala el APK.

## Compilar y publicar
- No hay Gradle wrapper ni Android Studio: **compila GitHub Actions** (`.github/workflows/build.yml`,
  Gradle 8.9, JDK 17). Cada push compila `assembleRelease` (firmado con la misma clave); el push a `main` publica la release
  `v1.0.<run_number>` con `Lazarus.apk` (versionCode = número de run).
- Corre `MatcherTest` (17 pruebas del seguimiento de la lectura) en todas las ramas, y en las que no son `main`
  también `VersePickerLiveTest` (necesita internet, `LAZARUS_LIVE=1`).
- Para probar en el celular la alarma en reposo: `docs/PRUEBAS_ALARMA.md`. Sin SDK de Android en el entorno de
  Claude Code web, la compilación real se verifica empujando a la rama y mirando Actions.
- Firma: `app/lazarus.p12` (cifrado) + secreto `LAZARUS_KEY_PASSWORD`. El workflow falla si falta el secreto
  o si la huella SHA-256 no coincide. **No cambiar la clave**: rompería las actualizaciones sobre lo instalado.
- Las acciones de GitHub están fijadas por hash de commit; mantenerlo al actualizarlas.

## Cuidado al trabajar
- minSdk 26, target/compile 34. El `applicationId` sigue siendo `ar.despertador.evangelio` (no cambiarlo:
  rompe la actualización); la marca es "Lazarus".
- Dispositivo de prueba: Motorola moto g35 5G (4 GB). Probar alarma en reposo / Doze a mano.
- No renombrar el repo con sesiones abiertas ni tener dos sesiones sobre el mismo repo.
- Convención de commits: mensajes en español, descriptivos.
- Pendiente sin decidir: versión para iPhone.
