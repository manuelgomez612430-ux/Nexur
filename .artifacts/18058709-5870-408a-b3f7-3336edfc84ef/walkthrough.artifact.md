# Walkthrough - Modo de Servicio y Selección de Rubro

Se ha implementado el soporte para dos tipos de negocio: **Venta de Productos** y **Venta de Servicios**. Ahora la aplicación adapta su interfaz y lógica según el rubro elegido.

## Cambios Principales

### 1. Nueva Pantalla de Selección de Rubro
Se ha creado una nueva actividad [BusinessSelectionActivity](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/java/com/naxor/app/BusinessSelectionActivity.kt) que se muestra inmediatamente después del inicio de sesión o registro si el usuario aún no ha configurado su rubro.

### 2. Adaptación de la Interfaz
- **Menú Principal:** El ítem de "Inventario" cambia su título a **"Servicios"** y usa un icono de agenda cuando el negocio es de servicios.
- **Pantalla de Inicio:** El botón de acción principal cambia de **"REALIZAR VENTA"** a **"REGISTRAR SERVICIO"**.
- **Gestión de Stock:** En el modo de servicios, se ocultan las columnas de "Stock" y "Costo" en la lista de productos/servicios para simplificar la vista.

### 3. Lógica de Venta
- En el modo de servicios, se ha desactivado la advertencia de "Stock Insuficiente" en [VentasActivity](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/java/com/naxor/app/VentasActivity.kt), permitiendo registrar servicios de forma ilimitada.

### 4. Configuración
Se ha añadido la opción de cambiar el rubro en cualquier momento desde la pantalla de [Ajustes](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/java/com/naxor/app/SettingsActivity.kt).

## Verificación

- [x] La pantalla de selección aparece tras el login.
- [x] El rubro se guarda en SharedPreferences y se sincroniza con Firestore.
- [x] La Bottom Navigation de `MainActivity` se actualiza correctamente.
- [x] `HomeFragment` adapta el texto del botón principal.
- [x] `StockFragment` (Inventario) oculta el stock en modo servicios.
- [x] `VentasActivity` omite validación de stock en modo servicios.
