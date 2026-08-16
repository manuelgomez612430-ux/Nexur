# Implementación de Buzón con Indicador de Notificación

Este plan detalla cómo agregar un buzón de mensajes en la barra lateral derecha y un indicador visual (punto rojo) para notificar nuevos mensajes al usuario.

## User Review Required

> [!NOTE]
> Para que el "desarrollador envíe mensajes", por ahora simularemos la recepción de un mensaje mediante una preferencia en el sistema. En una fase posterior, se podría integrar con Firebase Cloud Messaging (FCM).

## Proposed Changes

### UI / Layouts

#### [NEW] [menu_badge.xml](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/res/layout/menu_badge.xml)
Crear un diseño pequeño para el indicador (badge) que se mostrará dentro del menú lateral.

#### [MODIFY] [menu_main_drawer.xml](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/res/menu/menu_main_drawer.xml)
Asociar el `menu_badge.xml` al item `menu_mailbox` mediante `app:actionLayout`.

### Lógica de Aplicación

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/java/com/naxor/app/MainActivity.kt)
- Implementar la lógica para mostrar/ocultar el punto rojo tanto en el botón de la pantalla principal como dentro del menú lateral.
- Manejar el clic en "Buzón de Mensajes" (por ahora mostrará un Toast o abrirá una actividad vacía si se prefiere).
- Simular la verificación de mensajes nuevos en `onResume`.

## Verification Plan

### Automated Tests
- No se requieren pruebas automatizadas para este cambio estético inicial.

### Manual Verification
1. Abrir la app y verificar que el punto rojo aparezca si hay un "mensaje pendiente".
2. Abrir el menú lateral (derecha) y ver que el item "Buzón de Mensajes" también tenga el punto rojo.
3. Hacer clic en el buzón y verificar que el punto rojo desaparezca (indicando que se ha leído).
