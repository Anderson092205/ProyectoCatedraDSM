package com.example.udb_academic

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.udb_academic.datos.Autenticacion
import com.example.udb_academic.datos.RepositorioAcademico
import com.example.udb_academic.dominio.ReglasAcademicas
import com.example.udb_academic.modelo.*
import com.example.udb_academic.notificaciones.Recordatorios
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ActividadPrincipal : AppCompatActivity() {
    private val azul = Color.rgb(23, 39, 75)
    private val gris = Color.rgb(89, 101, 120)
    private val fondo = Color.rgb(245, 246, 250)
    private val dorado = Color.rgb(168, 122, 25)
    private var repositorio: RepositorioAcademico? = null
    private var pantalla = "inicio"
    private var filtroActividades = "todas"
    private var filtroApuntes = ""
    private var diaSeleccionado = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1).coerceAtMost(5)
    private lateinit var raiz: LinearLayout
    private lateinit var contenido: LinearLayout
    private var apunteParaAdjuntar = ""
    private var adjuntoParaDescargar: Adjunto? = null
    private val formatoFecha = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.forLanguageTag("es-SV"))
    private var probarAlDarPermiso = false
    private val permisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permitido ->
        if (probarAlDarPermiso) {
            if (permitido) Recordatorios.probar(this) else aviso("Permití las notificaciones en los ajustes de la aplicación para ver los recordatorios.", true)
            probarAlDarPermiso = false
        }
    }
    private val elegirArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) procesarAdjunto(uri) }
    private val guardarArchivo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val adjunto = adjuntoParaDescargar
        if (uri != null && adjunto != null) repositorio?.descargarAdjunto(adjunto, uri) { aviso("Archivo descargado.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inicialización base
    }

    /** Presenta el resumen del día, próximas entregas y últimos apuntes. */
    private fun mostrarInicio(datos: RepositorioAcademico) {
        texto(contenido, "¡Hola, ${datos.estudiante.nombre.substringBefore(" ").ifBlank { "estudiante" }}!", 28f, azul, true)
        texto(contenido, SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("es-SV")).format(Date()).replaceFirstChar { it.uppercase() })
        tarjeta(oscura = true) { panel ->
            texto(panel, "TU CICLO DE UN VISTAZO", 12f, Color.rgb(222, 193, 115), true)
            texto(panel, "${datos.materias.size} materias  ·  ${datos.actividades.count { it.estado == "pendiente" }} pendientes", 21f, Color.WHITE, true)
            texto(panel, "Promedio acumulado: ${ReglasAcademicas.notaTexto(ReglasAcademicas.calcularPromedioGlobal(datos.actividades))} / 10", 15f, Color.WHITE)
        }
        texto(contenido, "Clases de hoy", 20f, azul, true)
        val hoy = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        val clases = datos.horarios.filter { it.dia == hoy }.sortedBy { it.horaInicio }
        if (clases.isEmpty()) vacio("Hoy no tenés clases registradas.", "Organizá tu semana desde Horario.")
        clases.forEach { horario -> tarjeta { panel ->
            texto(panel, nombreMateria(horario.materiaId), 16f, azul, true)
            texto(panel, "${horario.horaInicio}–${horario.horaFin} · ${horario.aula}")
            boton(panel, "Ver asignatura", true) { navegar("materia:${horario.materiaId}") }
        } }
        texto(contenido, "Próximas entregas", 20f, azul, true)
        val pendientes = datos.actividades.filter { it.estado == "pendiente" }.sortedBy { it.fechaEntrega }.take(3)
        if (pendientes.isEmpty()) vacio("Estás al día", "Agregá tus evaluaciones desde Actividades.")
        pendientes.forEach { tarjetaActividad(it) }
        texto(contenido, "Últimos apuntes", 20f, azul, true)
        if (datos.apuntes.isEmpty()) texto(contenido, "Tus notas de clase aparecerán aquí.")
        datos.apuntes.takeLast(2).reversed().forEach { apunte -> tarjeta { panel ->
            texto(panel, apunte.titulo, 16f, azul, true); texto(panel, nombreMateria(apunte.materiaId), 12f); texto(panel, apunte.contenido.take(150))
            boton(panel, "Abrir apuntes", true) { navegar("apuntes") }
        } }
    }
    /** Muestra apuntes de texto y sus adjuntos privados con filtro por materia. */
    private fun mostrarApuntes(datos: RepositorioAcademico) {
        texto(contenido, "Apuntes y notas", 27f, azul, true)
        texto(contenido, "Ideas de clase, documentos e imágenes por materia.")
        texto(contenido, "Los adjuntos se guardan solo en este teléfono. Exportá una copia para conservarlos fuera de la aplicación.", 12f, dorado)
        boton(contenido, "+ Nuevo apunte") { if (exigirMaterias()) formularioApunte() }
        val identificadores = listOf("") + datos.materias.map { it.id }
        val filtro = selector(contenido, "Filtrar por materia", listOf("Todas las materias") + datos.materias.map { it.nombre }, identificadores.indexOf(filtroApuntes))
        filtro.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /** Actualiza la materia usada para filtrar los apuntes visibles. */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (filtroApuntes != identificadores[position]) { filtroApuntes = identificadores[position]; mostrarAplicacion() } }
            /** Mantiene la materia actual cuando no hay una selección nueva. */
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
        val apuntes = datos.apuntes.filter { filtroApuntes.isEmpty() || it.materiaId == filtroApuntes }.reversed()
        if (apuntes.isEmpty()) vacio("Guardá tus ideas de clase", "Cada apunte queda asociado a una materia.")
        apuntes.forEach { apunte -> tarjeta { panel ->
            texto(panel, nombreMateria(apunte.materiaId).uppercase(), 11f, dorado, true)
            texto(panel, apunte.titulo, 21f, azul, true)
            texto(panel, apunte.contenido)
            apunte.adjuntos.forEach { adjunto ->
                texto(panel, "${adjunto.nombre} · ${adjunto.tamano / 1024} KB", 13f, azul)
                boton(panel, "Guardar copia de ${adjunto.nombre}", true) { adjuntoParaDescargar = adjunto; guardarArchivo.launch(adjunto.nombre) }
                boton(panel, "Quitar adjunto", true) { confirmar("Eliminar adjunto", "Se eliminará ${adjunto.nombre} de este teléfono.") { datos.eliminarAdjunto(adjunto) { aviso("Adjunto local eliminado.") } } }
            }
            boton(panel, "Editar apunte", true) { formularioApunte(apunte) }
            boton(panel, "Adjuntar imagen o PDF", true) {
                if (apunte.adjuntos.size >= 5) aviso("Podés agregar hasta cinco adjuntos por apunte.")
                else { apunteParaAdjuntar = apunte.id; elegirArchivo.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")) }
            }
            boton(panel, "Eliminar apunte", true) {
                if (apunte.adjuntos.isNotEmpty()) aviso("Quitá primero los adjuntos para eliminar el apunte.", true)
                else confirmar("Eliminar apunte", "Se eliminará ${apunte.titulo}.") { datos.eliminar("apuntes", apunte.id) }
            }
        } }
    }

    /** Redacta un apunte de texto vinculado a una materia. */
    private fun formularioApunte(apunte: Apunte? = null) {
        val datos = repositorio ?: return
        dialogo(if (apunte == null) "Nuevo apunte" else "Editar apunte") { panel, error ->
            val titulo = entrada(panel, "Título del apunte", apunte?.titulo.orEmpty())
            val materia = selector(panel, "Materia", datos.materias.map { it.nombre }, datos.materias.indexOfFirst { it.id == apunte?.materiaId })
            val contenido = entrada(panel, "Contenido", apunte?.contenido.orEmpty(), multilinea = true)
            texto(panel, "Guardá el apunte para poder adjuntar imágenes o documentos PDF.", 12f)
            val guardar: () -> Boolean = {
                val nuevo = Apunte(apunte?.id ?: datos.nuevoId(), datos.materias[materia.selectedItemPosition].id, titulo.text.toString().trim(), contenido.text.toString().trim(), apunte?.adjuntos.orEmpty())
                if (nuevo.titulo.length !in 2..150 || nuevo.contenido.length !in 1..20000) { error.text = "Ingresá un título de 2 a 150 caracteres y contenido de hasta 20,000 caracteres."; false }
                else { datos.guardar("apuntes", nuevo.id, nuevo); true }
            }
            guardar
        }
    }

    /** Valida los metadatos del archivo seleccionado y lo adjunta al apunte existente. */
    private fun procesarAdjunto(uri: Uri) {
        val datos = repositorio ?: return
        val apunte = datos.apuntes.find { it.id == apunteParaAdjuntar } ?: run { aviso("Volvé a seleccionar el apunte."); return }
        val tipo = contentResolver.getType(uri).orEmpty()
        var nombre = "archivo"
        var tamano = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { consulta ->
            if (consulta.moveToFirst()) { nombre = consulta.getString(0); if (!consulta.isNull(1)) tamano = consulta.getLong(1) }
        }
        if (tipo !in listOf("application/pdf", "image/jpeg", "image/png", "image/webp") || (tamano > 10485760 || tamano == 0L) || nombre.length !in 1..180) { aviso("Elegí un PDF o imagen de hasta 10 MB con un nombre de hasta 180 caracteres.", true); return }
        aviso("Guardando archivo en el teléfono…")
        datos.guardarAdjuntoLocal(apunte.id, uri, nombre, tipo) { aviso("Adjunto guardado en este dispositivo.") }
    }
}