# Pruebas de la alarma en reposo (a mano, en el celular)

Estas pruebas no se pueden automatizar: hay que hacerlas en el teléfono real (moto g35). Necesitan `adb`
con el celular conectado por USB (depuración USB activada). Antes de empezar: permisos en verde en la
pestaña Ajustes (alarmas exactas, notificaciones, micrófono, mostrar sobre otras apps, batería sin restricciones).

Para cada prueba: crear una alarma a 3–5 minutos, hacer lo indicado y comprobar que **suena a la hora**
y que la pantalla de lectura aparece sola.

| # | Qué probar | Cómo | Esperado |
|---|-----------|------|----------|
| 1 | Pantalla bloqueada | Bloquear y dejar el celular quieto | Suena y abre la lectura |
| 2 | Doze profundo | `adb shell dumpsys deviceidle force-idle` tras bloquear | Suena a la hora exacta |
| 3 | Ahorro de batería | Activar "Ahorro de batería" del sistema | Suena a la hora |
| 4 | App cerrada | Sacarla de recientes (deslizar) | Suena a la hora |
| 5 | Proceso muerto | `adb shell am kill ar.despertador.evangelio` | Suena a la hora |
| 6 | Reinicio | Reiniciar el celular con la alarma ya creada | Sigue programada, suena |
| 7 | Cambio de zona horaria | Cambiar la zona en Ajustes del sistema | La hora de la alarma sigue siendo la local nueva |
| 8 | Cambio de hora manual | Adelantar/atrasar el reloj | La próxima se recalcula bien |
| 9 | Repetición | Alarma con días: dejar que suene y apagarla | Queda programada la del día siguiente |
| 10 | Una sola vez | Alarma sin días | Suena y queda desactivada |
| 11 | Sin internet | Modo avión la noche anterior | Muestra el Evangelio guardado, no el de reserva |
| 12 | Llamada / otra app de audio | Sonar con música reproduciendo | La alarma se oye igual |

Salir de Doze al terminar: `adb shell dumpsys deviceidle unforce`.

Ver qué programó la app:

```bash
adb shell dumpsys alarm | grep -A3 ar.despertador.evangelio
```

Si una prueba falla, anotar el número, la hora y la versión de la app (Ajustes → Actualizaciones).
