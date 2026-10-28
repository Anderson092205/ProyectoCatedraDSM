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
}