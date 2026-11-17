package com.gomezmart.app

import android.content.Context
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gomezmart.app.databinding.ActivityAiChatBinding
import com.gomezmart.app.databinding.ItemAiChatMessageOtherBinding
import com.gomezmart.app.databinding.ItemAiChatTypingBinding
import com.gomezmart.app.databinding.ItemChatMessageMeBinding
import com.gomezmart.app.util.GeminiHelper
import com.gomezmart.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AiChatActivity : BaseActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private lateinit var adapter: AiChatAdapter
    private val messages = mutableListOf<AiMessage>()

    data class AiMessage(
        val text: String,
        val isFromUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val whatsappMessage: String? = null,
        val clientPhone: String? = null,
        val isTyping: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requireLoginForGuest("usar el Asesor IA")) {
            finish()
            return
        }
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.appBarAiChat, applyTop = true, applyBottom = false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.layoutAiBottomContainer) { v, insets ->
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomInset = if (imeHeight > 0) imeHeight else navBarHeight
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInset)
            insets
        }

        binding.toolbarAiChat.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadSavedMessages()
        setupListeners()
        adapter.notifyDataSetChanged()
    }

    private fun saveMessages() {
        try {
            val prefs = getSharedPreferences("AiChatPrefs", Context.MODE_PRIVATE)
            val json = com.google.gson.Gson().toJson(messages)
            prefs.edit().putString("saved_ai_messages", json).apply()
        } catch (e: Exception) {}
    }

    private fun loadSavedMessages() {
        try {
            val prefs = getSharedPreferences("AiChatPrefs", Context.MODE_PRIVATE)
            val json = prefs.getString("saved_ai_messages", null)
            if (!json.isNullOrEmpty()) {
                val type = object : com.google.gson.reflect.TypeToken<List<AiMessage>>() {}.type
                val savedList: List<AiMessage> = com.google.gson.Gson().fromJson(json, type)
                if (!savedList.isNullOrEmpty()) {
                    messages.clear()
                    messages.addAll(savedList)
                }
            }
        } catch (e: Exception) {}

        if (messages.isEmpty()) {
            messages.add(
                AiMessage(
                    "¡Hola! Soy tu Asesor Financiero IA de GoMezMart 🤖. Pregúntame lo que desees sobre tus ventas, préstamos, deudores, morosidad o cómo optimizar la rentabilidad de tu negocio.",
                    false
                )
            )
            saveMessages()
        }
    }

    private fun setupRecyclerView() {
        adapter = AiChatAdapter(messages)
        binding.rvAiMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvAiMessages.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnSendAiMessage.setOnClickListener {
            sendMessage()
        }

        binding.btnActionWhatsapp.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@AiChatActivity)
                val clients = db.loanDao().getAllClientsOnce()
                val loans = db.loanDao().getAllLoansOnce()
                val installments = db.loanDao().getAllInstallmentsOnce()

                val overduePairs = clients.mapNotNull { client ->
                    val cLoans = loans.filter { it.clientId == client.id && !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }
                    val cInsts = installments.filter { inst -> cLoans.any { loan -> loan.id == inst.loanId } && inst.status != "PAID" && !inst.isDeleted }
                    val debt = cInsts.sumOf { it.amount - it.amountPaid }
                    if (debt > 0.01 && client.phone.isNotBlank()) Pair(client, debt) else null
                }

                withContext(Dispatchers.Main) {
                    if (overduePairs.isEmpty()) {
                        Toast.makeText(this@AiChatActivity, "No hay clientes con deuda pendiente", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val names = overduePairs.map { "${it.first.name} (S/ ${String.format(Locale.US, "%.2f", it.second)})" }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this@AiChatActivity)
                        .setTitle("📲 Enviar WhatsApp a Cliente")
                        .setItems(names) { _, which ->
                            val (client, debt) = overduePairs[which]
                            val msg = "Hola ${client.name}, te escribo de GoMezMart para recordarte amablemente tu saldo pendiente de S/ ${String.format(Locale.US, "%.2f", debt)}. Quedamos atentos a tu abono. ¡Muchas gracias! 🤝"
                            val cleanPhone = client.phone.replace(Regex("[^0-9+]"), "")
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${android.net.Uri.encode(msg)}")
                            }
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(this@AiChatActivity, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .show()
                }
            }
        }

        binding.btnActionNewLoan.setOnClickListener {
            val intent = android.content.Intent().setClassName(packageName, "com.gomezmart.feature_loans.AddLoanActivity")
            startActivity(intent)
        }

        binding.btnActionExpenses.setOnClickListener {
            val bType = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                .getString("business_type", "LOANS") ?: "LOANS"
            if (bType == "LOANS") {
                val intent = android.content.Intent().setClassName(packageName, "com.gomezmart.feature_loans.LoansExpensesActivity")
                startActivity(intent)
            } else {
                startActivity(android.content.Intent(this, GastosActivity::class.java))
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etAiMessage.text.toString().trim()
        if (text.isEmpty()) return

        messages.add(AiMessage(text, true))
        saveMessages()
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvAiMessages.smoothScrollToPosition(messages.size - 1)
        binding.etAiMessage.setText("")

        // Mensaje temporal con animación de "escribiendo"
        val thinkingIndex = messages.size
        messages.add(AiMessage("", false, isTyping = true))
        adapter.notifyItemInserted(thinkingIndex)
        binding.rvAiMessages.smoothScrollToPosition(thinkingIndex)

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@AiChatActivity)
            val loans = db.loanDao().getAllLoansOnce()
            val installments = db.loanDao().getAllInstallmentsOnce()
            val expenses = db.loanDao().getAllLoanExpensesOnce()
            val clients = db.loanDao().getAllClientsOnce()

            val activeLoans = loans.filter { !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }
            val totalLent = activeLoans.sumOf { it.amount }
            val pendingBalance = installments.filter { it.status != "PAID" && !it.isDeleted }.sumOf { it.amount - it.amountPaid }
            
            var totalProfit = 0.0
            val payments = db.loanDao().getAllPaymentsOnce()
            payments.forEach { p ->
                val loan = loans.find { it.id == p.loanId }
                loan?.let {
                    val interestRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                    totalProfit += (p.amount * interestRatio) + p.lateFeeAmount
                }
            }
            val businessExpenses = expenses.filter { !it.isPersonal }.sumOf { it.amount }
            val netProfit = totalProfit - businessExpenses
            
            val now = System.currentTimeMillis()
            val overdueCount = activeLoans.count { loan ->
                installments.any { it.loanId == loan.id && it.status != "PAID" && it.dueDate < now }
            }

            val clientDetails = clients.map { client ->
                val clientLoans = activeLoans.filter { it.clientId == client.id }
                val clientInsts = installments.filter { inst -> clientLoans.any { it.id == inst.loanId } && inst.status != "PAID" && !inst.isDeleted }
                val debt = clientInsts.sumOf { it.amount - it.amountPaid }
                "• Cliente: ${client.name} | Tel: ${client.phone} | Deuda pendiente: S/ ${String.format(Locale.US, "%.2f", debt)}"
            }.joinToString("\n")

            // Solo los últimos mensajes para no saturar el contexto de la IA
            val history = messages.drop(1).takeLast(10).joinToString("\n") {
                (if (it.isFromUser) "Usuario: " else "IA: ") + it.text.take(600)
            }

            val answer = kotlinx.coroutines.withTimeoutOrNull(60000L) {
                GeminiHelper.askBusinessAssistant(
                    context = this@AiChatActivity,
                    totalLent = totalLent,
                    pendingBalance = pendingBalance,
                    netProfit = netProfit,
                    expenses = businessExpenses,
                    clientsCount = clients.size,
                    overdueCount = overdueCount,
                    futureCommitments = 0.0,
                    clientDetailsContext = clientDetails,
                    conversationHistory = history,
                    userQuestion = text
                )
            } ?: "Lo siento, la IA tardó demasiado en responder en este momento. Por favor, inténtalo de nuevo."

            val mentionedClient = clients.find { client -> answer.contains(client.name, ignoreCase = true) }
            val clientPhone = mentionedClient?.phone

            val regex = Regex("['\"]([^'\"]{15,})['\"]")
            val match = regex.find(answer)
            val extractedWhatsappMsg = match?.groups?.get(1)?.value

            withContext(Dispatchers.Main) {
                messages[thinkingIndex] = AiMessage(
                    text = answer,
                    isFromUser = false,
                    whatsappMessage = extractedWhatsappMsg,
                    clientPhone = clientPhone
                )
                saveMessages()
                // Se notifica el cambio de tipo de vista (typing -> mensaje real)
                adapter.notifyItemChanged(thinkingIndex, PAYLOAD_TYPING_REPLACED)
                binding.rvAiMessages.smoothScrollToPosition(thinkingIndex)
            }
        }
    }

    class AiChatAdapter(private val list: List<AiMessage>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val VIEW_TYPE_ME = 1
        private val VIEW_TYPE_OTHER = 2
        private val VIEW_TYPE_TYPING = 3

        override fun getItemViewType(position: Int): Int {
            val msg = list[position]
            return when {
                msg.isTyping -> VIEW_TYPE_TYPING
                msg.isFromUser -> VIEW_TYPE_ME
                else -> VIEW_TYPE_OTHER
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_ME -> MeViewHolder(ItemChatMessageMeBinding.inflate(inflater, parent, false))
                VIEW_TYPE_TYPING -> TypingViewHolder(ItemAiChatTypingBinding.inflate(inflater, parent, false))
                else -> OtherViewHolder(ItemAiChatMessageOtherBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = list[position]
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val timeStr = sdf.format(Date(msg.timestamp))

            if (holder is TypingViewHolder) {
                holder.startAnimation()
            } else if (holder is MeViewHolder) {
                holder.binding.tvMessageText.text = msg.text
                holder.binding.tvMessageTime.text = timeStr
            } else if (holder is OtherViewHolder) {
                holder.binding.tvMessageText.text = GeminiHelper.formatMarkdown(msg.text)
                holder.binding.tvMessageTime.text = timeStr

                if (!msg.whatsappMessage.isNullOrEmpty()) {
                    holder.binding.btnSendWhatsappBubble.visibility = View.VISIBLE
                    holder.binding.btnSendWhatsappBubble.setOnClickListener {
                        val cleanPhone = msg.clientPhone?.replace(Regex("[^0-9+]"), "") ?: ""
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse(
                                if (cleanPhone.isNotEmpty()) "https://api.whatsapp.com/send?phone=$cleanPhone&text=${android.net.Uri.encode(msg.whatsappMessage)}"
                                else "https://api.whatsapp.com/send?text=${android.net.Uri.encode(msg.whatsappMessage)}"
                            )
                        }
                        try {
                            holder.itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(holder.itemView.context, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    holder.binding.btnSendWhatsappBubble.visibility = View.GONE
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is TypingViewHolder) holder.stopAnimation()
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = list.size

        class MeViewHolder(val binding: ItemChatMessageMeBinding) : RecyclerView.ViewHolder(binding.root)
        class OtherViewHolder(val binding: ItemAiChatMessageOtherBinding) : RecyclerView.ViewHolder(binding.root)

        /** Soporte con los tres puntos rebotando + robot pulsante. */
        class TypingViewHolder(val binding: ItemAiChatTypingBinding) : RecyclerView.ViewHolder(binding.root) {
            private val animators = mutableListOf<ObjectAnimator>()

            fun startAnimation() {
                stopAnimation()
                val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
                dots.forEachIndexed { index, dot ->
                    val bounce = ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -9f).apply {
                        duration = 550L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        startDelay = index * 170L
                    }
                    val fade = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f).apply {
                        duration = 550L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        startDelay = index * 170L
                    }
                    animators.add(bounce)
                    animators.add(fade)
                    bounce.start()
                    fade.start()
                }

                // El robot parpadea suavemente
                val robot = ObjectAnimator.ofFloat(binding.tvTypingRobot, View.ALPHA, 0.45f, 1f).apply {
                    duration = 700L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                }
                animators.add(robot)
                robot.start()
            }

            fun stopAnimation() {
                animators.forEach { it.cancel() }
                animators.clear()
            }
        }
    }

    companion object {
        private const val PAYLOAD_TYPING_REPLACED = "typing_replaced"
    }
}
