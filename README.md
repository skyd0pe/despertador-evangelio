# Lazarus

*«Lázaro, sal fuera» (Jn 11, 43)*

App de Android: un despertador que no se apaga hasta que leés en voz alta el Evangelio del día.

## Cómo funciona

1. A la hora fijada suena, aunque el celular esté bloqueado.
2. Aparece el Evangelio del día (El Libro del Pueblo de Dios, vía evangeliodeldia.org).
3. Al tocar **Empezar a leer** se corta el sonido y el reconocimiento de voz sigue tu lectura palabra por palabra.
4. Si llegás al final habiendo leído bien el porcentaje fijado (80 % por defecto), se apaga.
   Si no, o si te quedás callado, vuelve a sonar.

## Qué se puede configurar

- Varias alarmas, cada una con sus días de la semana (o una sola vez).
- Tema: sistema, claro, oscuro o AMOLED (negro puro).
- Tamaño de la letra, con muestra.
- Modo ecuménico: oculta el calendario litúrgico y las fórmulas católicas; deja solo el texto bíblico.
- Cuánto hay que leer bien, a los cuántos segundos de silencio vuelve a sonar y volumen progresivo.

Un punto de color marca el tiempo litúrgico (verde, morado, blanco, rojo, rosa). Al terminar de leer se suma un día a la racha.

Los botones de volumen y "atrás" no apagan la alarma. Para emergencias, en la pantalla de la alarma
hay que mantener apretado **Emergencia** durante 8 segundos.

## Instalar

Cada cambio que se sube se compila solo. La última versión está en **Releases** → `Lazarus.apk`.
Descargala desde el celular e instalala; las versiones nuevas se instalan encima sin perder los ajustes.

Tipografías de la marca: Fraunces, Inter y Literata (SIL Open Font License), incluidas en `app/src/main/assets/fonts` con sus licencias.

## Firma

La app se firma con la clave privada `app/lazarus.p12`, cifrada con AES-256. Su contraseña está solo
en el secreto `LAZARUS_KEY_PASSWORD` del repositorio. La compilación se detiene si falta el secreto o
si el instalador no queda firmado con esa clave (huella SHA-256 `6b14a077…6b41f9`).
