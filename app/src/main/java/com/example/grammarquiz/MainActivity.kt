package com.example.grammarquiz

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as GColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// ۱. مدل‌های داده و منطق ارزیابی چندبعدی
// ---------------------------------------------------------------------------

enum class ConfidenceLevel(val label: String, val weightCorrect: Float, val penaltyWrong: Float) {
    LOW("حدس (Low)", 1.0f, 0.0f),
    MEDIUM("مطمئن (Medium)", 2.0f, 0.0f),
    HIGH("مسلط (High)", 3.0f, -1.0f)
}

enum class AnswerStatus { EXACT, TYPO, WRONG }
enum class MasteryLevel { STRENGTH, MODERATE, WEAKNESS }

data class Question(
    val prompt: String,
    val answers: List<String>,
    val explanation: String,
    val tag: String,
    val hints: List<String>
)

data class QuestionResult(
    val prompt: String,
    val userAnswer: String,
    val status: AnswerStatus,
    val confidence: ConfidenceLevel,
    val scoreEarned: Float,
    val maxPossibleScore: Float,
    val hintsUsedCount: Int,
    val expected: String,
    val explanation: String,
    val tag: String
)

data class TopicAnalytics(
    val tag: String,
    val earnedScore: Float,
    val totalMaxScore: Float,
    val correctCount: Int,
    val totalCount: Int
) {
    val percentage: Float
        get() = if (totalMaxScore > 0f) (earnedScore / totalMaxScore) * 100f else 0f

    val level: MasteryLevel
        get() = when {
            percentage >= 75f -> MasteryLevel.STRENGTH
            percentage >= 50f -> MasteryLevel.MODERATE
            else -> MasteryLevel.WEAKNESS
        }
}

// ---------------------------------------------------------------------------
// ۲. الگوریتم‌های تطبیق فازی و پردازش متن
// ---------------------------------------------------------------------------

fun normalizeText(text: String): String {
    if (text.isBlank()) return ""
    var str = text.replace("’", "'").replace("‘", "'").replace("`", "'")
        .replace("“", "\"").replace("”", "\"")
    str = str.replace(Regex("^[\\s.,!?;:\"']+|[\\s.,!?;:\"']+$"), "")
    str = str.replace(Regex("\\s+"), " ")
    return str.trim().lowercase()
}

fun levenshteinDistance(s1: String, s2: String): Int {
    if (s1.length < s2.length) return levenshteinDistance(s2, s1)
    if (s2.isEmpty()) return s1.length
    var prev = IntArray(s2.length + 1) { it }
    for (i in s1.indices) {
        val curr = IntArray(s2.length + 1)
        curr[0] = i + 1
        for (j in s2.indices) {
            val ins = prev[j + 1] + 1
            val del = curr[j] + 1
            val sub = prev[j] + if (s1[i] != s2[j]) 1 else 0
            curr[j + 1] = minOf(ins, del, sub)
        }
        prev = curr
    }
    return prev[s2.length]
}

fun evaluateAnswer(userInput: String, validAnswers: List<String>): Pair<AnswerStatus, String> {
    val normUser = normalizeText(userInput)
    if (normUser.isEmpty()) return Pair(AnswerStatus.WRONG, validAnswers.firstOrNull() ?: "")

    for (target in validAnswers) {
        if (normUser == normalizeText(target)) return Pair(AnswerStatus.EXACT, target)
    }

    for (target in validAnswers) {
        val normTarget = normalizeText(target)
        val maxDist = if (normTarget.length < 5) 0 else if (normTarget.length < 10) 1 else 2
        if (maxDist > 0 && levenshteinDistance(normUser, normTarget) <= maxDist) {
            return Pair(AnswerStatus.TYPO, target)
        }
    }

    return Pair(AnswerStatus.WRONG, validAnswers.firstOrNull() ?: "")
}

fun calculateAnalytics(results: List<QuestionResult>): List<TopicAnalytics> {
    return results.groupBy { it.tag }.map { (tag, items) ->
        val earned = items.sumOf { it.scoreEarned.toDouble() }.toFloat()
        val maxScore = items.sumOf { it.maxPossibleScore.toDouble() }.toFloat()
        val correct = items.count { it.status != AnswerStatus.WRONG }
        TopicAnalytics(tag, earned, maxScore, correct, items.size)
    }.sortedBy { it.percentage }
}

// ---------------------------------------------------------------------------
// ۳. کلاس رابط چاپگر اندروید (PrintDocumentAdapter)
// ---------------------------------------------------------------------------

class PdfPrintDocumentAdapter(private val pdfFile: File) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(pdfFile.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()

        callback?.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null

        try {
            input = FileInputStream(pdfFile)
            output = FileOutputStream(destination?.fileDescriptor)

            val buffer = ByteArray(4096)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } >= 0) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onWriteCancelled()
                    return
                }
                output.write(buffer, 0, bytesRead)
            }

            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback?.onWriteFailed(e.message)
        } finally {
            try { input?.close() } catch (ignored: Exception) {}
            try { output?.close() } catch (ignored: Exception) {}
        }
    }
}

// ---------------------------------------------------------------------------
// ۴. موتور چندصفحه‌ای تولید کارنامه رسمی PDF
// ---------------------------------------------------------------------------

object MultiPagePdfManager {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_LEFT = 30f
    private const val MARGIN_RIGHT = 565f
    private const val MAX_CONTENT_Y = 780f

    fun createPdfReport(
        context: Context,
        studentName: String,
        results: List<QuestionResult>,
        analytics: List<TopicAnalytics>,
        totalTimeSeconds: Int
    ): File {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var pageIndex = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create()
        var currentPage = document.startPage(pageInfo)
        var canvas = currentPage.canvas

        var currentY = 0f

        fun drawHeaderBanner(c: Canvas) {
            paint.color = GColor.parseColor("#1E293B")
            c.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 75f, paint)

            paint.color = GColor.WHITE
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 16f
            c.drawText("DIAGNOSTIC GRAMMAR REPORT (CBM)", MARGIN_LEFT, 36f, paint)

            paint.textSize = 9f
            paint.color = GColor.parseColor("#94A3B8")
            paint.typeface = Typeface.DEFAULT
            c.drawText("Confidence-Based Evaluation & Mastery Breakdown", MARGIN_LEFT, 54f, paint)
        }

        fun drawCompactHeader(c: Canvas) {
            paint.color = GColor.parseColor("#1E293B")
            c.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 35f, paint)

            paint.color = GColor.WHITE
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 11f
            c.drawText("DIAGNOSTIC REPORT — $studentName (Continued)", MARGIN_LEFT, 22f, paint)
        }

        fun drawFooter(c: Canvas, pageNumber: Int) {
            paint.color = GColor.parseColor("#CBD5E1")
            paint.strokeWidth = 1f
            c.drawLine(MARGIN_LEFT, (PAGE_HEIGHT - 35).toFloat(), MARGIN_RIGHT, (PAGE_HEIGHT - 35).toFloat(), paint)

            paint.color = GColor.parseColor("#94A3B8")
            paint.textSize = 8.5f
            paint.typeface = Typeface.DEFAULT
            c.drawText("Confidential Educational Record • Generated Automatically", MARGIN_LEFT, (PAGE_HEIGHT - 20).toFloat(), paint)
            c.drawText("Page $pageNumber", MARGIN_RIGHT - 40f, (PAGE_HEIGHT - 20).toFloat(), paint)
        }

        fun ensureSpace(neededHeight: Float) {
            if (currentY + neededHeight > MAX_CONTENT_Y) {
                drawFooter(canvas, pageIndex)
                document.finishPage(currentPage)

                pageIndex++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create()
                currentPage = document.startPage(pageInfo)
                canvas = currentPage.canvas

                drawCompactHeader(canvas)
                currentY = 55f
            }
        }

        drawHeaderBanner(canvas)
        currentY = 95f

        val totalEarned = results.sumOf { it.scoreEarned.toDouble() }.toFloat()
        val totalMax = results.sumOf { it.maxPossibleScore.toDouble() }.toFloat()
        val percentage = if (totalMax > 0) ((totalEarned / totalMax) * 100).toInt() else 0
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        val cardHeight = 70f
        val cardRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + cardHeight)
        paint.color = GColor.parseColor("#F8FAFC")
        canvas.drawRoundRect(cardRect, 8f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.color = GColor.parseColor("#E2E8F0")
        canvas.drawRoundRect(cardRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        paint.color = GColor.parseColor("#1E293B")
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 12f
        canvas.drawText("Student: $studentName", MARGIN_LEFT + 15f, currentY + 28f, paint)

        paint.typeface = Typeface.DEFAULT
        paint.textSize = 9.5f
        paint.color = GColor.parseColor("#64748B")
        canvas.drawText("Date: $dateStr  •  Duration: ${totalTimeSeconds}s", MARGIN_LEFT + 15f, currentY + 50f, paint)

        paint.color = if (percentage >= 60) GColor.parseColor("#166534") else GColor.parseColor("#991B1B")
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 18f
        val scoreText = "${String.format(Locale.US, "%.1f", totalEarned)} / ${totalMax.toInt()} ($percentage%)"
        val textWidth = paint.measureText(scoreText)
        canvas.drawText(scoreText, MARGIN_RIGHT - textWidth - 15f, currentY + 42f, paint)

        currentY += cardHeight + 25f

        ensureSpace(40f)
        paint.color = GColor.parseColor("#0F172A")
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 12.5f
        canvas.drawText("Topic Mastery Analysis", MARGIN_LEFT, currentY, paint)

        paint.color = GColor.parseColor("#CBD5E1")
        paint.strokeWidth = 1f
        canvas.drawLine(MARGIN_LEFT, currentY + 6f, MARGIN_RIGHT, currentY + 6f, paint)
        currentY += 22f

        analytics.forEach { a ->
            ensureSpace(32f)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 10f
            paint.color = GColor.parseColor("#1E293B")
            canvas.drawText(a.tag, MARGIN_LEFT, currentY, paint)

            val (statusLabel, hexColor) = when (a.level) {
                MasteryLevel.STRENGTH -> Pair("Strength", "#22C55E")
                MasteryLevel.MODERATE -> Pair("Review Needed", "#EAB308")
                MasteryLevel.WEAKNESS -> Pair("Focus Area (Weakness)", "#EF4444")
            }

            paint.typeface = Typeface.DEFAULT
            paint.textSize = 9.5f
            paint.color = GColor.parseColor(hexColor)
            val info = "${String.format(Locale.US, "%.1f", a.earnedScore)}/${a.totalMaxScore.toInt()} (${a.percentage.toInt()}%) — $statusLabel"
            val infoW = paint.measureText(info)
            canvas.drawText(info, MARGIN_RIGHT - infoW, currentY, paint)

            val barY = currentY + 5f
            val fullBarW = MARGIN_RIGHT - MARGIN_LEFT
            paint.color = GColor.parseColor("#E2E8F0")
            canvas.drawRoundRect(RectF(MARGIN_LEFT, barY, MARGIN_RIGHT, barY + 6f), 3f, 3f, paint)

            val fillW = (a.percentage / 100f) * fullBarW
            if (fillW > 0) {
                paint.color = GColor.parseColor(hexColor)
                canvas.drawRoundRect(RectF(MARGIN_LEFT, barY, MARGIN_LEFT + fillW, barY + 6f), 3f, 3f, paint)
            }
            currentY += 28f
        }

        currentY += 15f

        ensureSpace(40f)
        paint.color = GColor.parseColor("#0F172A")
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 12.5f
        canvas.drawText("Detailed Question Breakdown", MARGIN_LEFT, currentY, paint)

        paint.color = GColor.parseColor("#CBD5E1")
        paint.strokeWidth = 1f
        canvas.drawLine(MARGIN_LEFT, currentY + 6f, MARGIN_RIGHT, currentY + 6f, paint)
        currentY += 20f

        results.forEachIndexed { idx, r ->
            val isWrong = r.status == AnswerStatus.WRONG
            val itemBoxHeight = if (isWrong) 68f else 52f

            ensureSpace(itemBoxHeight + 8f)

            val itemRect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + itemBoxHeight)
            val (bgHex, borderHex) = when (r.status) {
                AnswerStatus.EXACT -> Pair("#F0FDF4", "#BBF7D0")
                AnswerStatus.TYPO -> Pair("#FEFCE8", "#FEF08A")
                AnswerStatus.WRONG -> Pair("#FEF2F2", "#FECACA")
            }

            paint.color = GColor.parseColor(bgHex)
            canvas.drawRoundRect(itemRect, 6f, 6f, paint)

            paint.style = Paint.Style.STROKE
            paint.color = GColor.parseColor(borderHex)
            canvas.drawRoundRect(itemRect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            paint.color = GColor.parseColor("#1E293B")
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 9.5f
            canvas.drawText("${idx + 1}. ${r.prompt}", MARGIN_LEFT + 10f, currentY + 16f, paint)

            paint.typeface = Typeface.DEFAULT
            paint.textSize = 8.5f
            paint.color = GColor.parseColor("#475569")
            val userText = if (r.userAnswer.isBlank()) "(Empty)" else r.userAnswer
            canvas.drawText("Answer: $userText  |  Confidence: ${r.confidence.name}  |  Score: ${r.scoreEarned}/${r.maxPossibleScore}", MARGIN_LEFT + 10f, currentY + 32f, paint)

            if (isWrong) {
                paint.color = GColor.parseColor("#991B1B")
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Expected: ${r.expected}", MARGIN_LEFT + 10f, currentY + 48f, paint)

                paint.color = GColor.parseColor("#64748B")
                paint.typeface = Typeface.DEFAULT
                canvas.drawText("Rule: ${r.explanation}", MARGIN_LEFT + 10f, currentY + 60f, paint)
            } else {
                paint.color = GColor.parseColor("#166534")
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val statusMsg = if (r.status == AnswerStatus.EXACT) "✓ Correct" else "✓ Accepted (Typo Noted)"
                canvas.drawText(statusMsg, MARGIN_LEFT + 10f, currentY + 44f, paint)
            }

            currentY += itemBoxHeight + 8f
        }

        drawFooter(canvas, pageIndex)
        document.finishPage(currentPage)

        val safeName = studentName.replace(Regex("\\s+"), "_")
        val outFile = File(context.cacheDir, "Report_${safeName}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { document.writeTo(it) }
        document.close()

        return outFile
    }

    fun sharePdf(context: Context, file: File, name: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Diagnostic Report - $name")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF Report"))
    }

    fun printPdf(context: Context, file: File, name: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printManager?.print("Report_$name", PdfPrintDocumentAdapter(file), printAttributes)
    }
}

// ---------------------------------------------------------------------------
// ۵. کامپوننت‌های رابط کاربری Jetpack Compose
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GrammarDiagnosticApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarDiagnosticApp() {
    val context = LocalContext.current

    val questions = remember {
        listOf(
            Question(
                prompt = "If she _____ (study) harder, she would have passed the exam.",
                answers = listOf("had studied", "'d studied"),
                explanation = "Third conditional requires: If + past perfect, would have + V3.",
                tag = "Conditionals",
                hints = listOf("مربوط به شرطی نوع سوم است.", "فرمول: had + V3")
            ),
            Question(
                prompt = "The new bridge _____ (complete) by engineers next month.",
                answers = listOf("will be completed", "'ll be completed"),
                explanation = "Future simple passive: will be + V3.",
                tag = "Passive Voice",
                hints = listOf("فاعل مفعول عمل است (مجهول).", "فرمول: will be + V3")
            ),
            Question(
                prompt = "He avoided _____ (answer) the teacher's question.",
                answers = listOf("answering"),
                explanation = "The verb 'avoid' is followed by a gerund (-ing).",
                tag = "Gerunds & Infinitives",
                hints = listOf("فعل avoid با اسم مصدر (-ing) می‌آید.")
            ),
            Question(
                prompt = "They _____ (live) in this city since 2015.",
                answers = listOf("have lived", "'ve lived", "have been living", "'ve been living"),
                explanation = "Present perfect with 'since' indicates duration until now.",
                tag = "Verb Tenses",
                hints = listOf("کلمه since نشانه حال کامل است.", "از have + V3 استفاده کنید.")
            )
        )
    }

    var studentName by remember { mutableStateOf("") }
    var isStarted by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(600) }

    var userAnswers by remember { mutableStateOf(List(questions.size) { "" }) }
    var userConfidence by remember { mutableStateOf(List(questions.size) { ConfidenceLevel.MEDIUM }) }
    var hintsUsed by remember { mutableStateOf(List(questions.size) { 0 }) }
    var results by remember { mutableStateOf(listOf<QuestionResult>()) }
    var totalTimeSpent by remember { mutableIntStateOf(0) }

    fun submitQuiz() {
        if (isFinished) return
        isFinished = true
        totalTimeSpent = 600 - remainingSeconds

        results = questions.mapIndexed { idx, q ->
            val input = userAnswers[idx]
            val conf = userConfidence[idx]
            val hintsCount = hintsUsed[idx]
            val (status, expected) = evaluateAnswer(input, q.answers)

            val baseScore = if (status != AnswerStatus.WRONG) conf.weightCorrect else conf.penaltyWrong
            val hintDeduction = hintsCount * 0.5f
            val finalScore = maxOf(0f, baseScore - hintDeduction)

            QuestionResult(
                prompt = q.prompt,
                userAnswer = input,
                status = status,
                confidence = conf,
                scoreEarned = finalScore,
                maxPossibleScore = 3.0f,
                hintsUsedCount = hintsCount,
                expected = expected,
                explanation = q.explanation,
                tag = q.tag
            )
        }
    }

    LaunchedEffect(isStarted, isFinished) {
        if (isStarted && !isFinished) {
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
            submitQuiz()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grammar Catalyst", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A), titleContentColor = Color.White),
                actions = {
                    if (isStarted && !isFinished) {
                        val m = remainingSeconds / 60
                        val s = remainingSeconds % 60
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .background(if (remainingSeconds <= 60) Color(0xFFEF4444) else Color(0xFF334155), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(String.format(Locale.US, "%02d:%02d", m, s), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF1F5F9))
        ) {
            if (!isStarted) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("آزمون گرامر شناختی (CBM)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("ارزیابی هوشمند سرفصل‌ها به همراه کارنامه چندصفحه‌ای و چاپ", fontSize = 13.sp, color = Color(0xFF64748B))
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedTextField(
                        value = studentName,
                        onValueChange = { studentName = it },
                        label = { Text("نام و نام خانوادگی دانش‌آموز") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { isStarted = true },
                        enabled = studentName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("شروع آزمون", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            } else if (!isFinished) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    itemsIndexed(questions) { idx, q ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${idx + 1}. ${q.prompt}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1E293B), modifier = Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier.background(Color(0xFFEFF6FF), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(q.tag, color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = userAnswers[idx],
                                    onValueChange = { newVal ->
                                        val u = userAnswers.toMutableList()
                                        u[idx] = newVal
                                        userAnswers = u
                                    },
                                    placeholder = { Text("پاسخ خود را بنویسید...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("میزان اطمینان به پاسخ:", fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ConfidenceLevel.values().forEach { level ->
                                        val isSelected = userConfidence[idx] == level
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(if (isSelected) Color(0xFF2563EB) else Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    val uc = userConfidence.toMutableList()
                                                    uc[idx] = level
                                                    userConfidence = uc
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(level.label, color = if (isSelected) Color.White else Color(0xFF475569), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                val used = hintsUsed[idx]
                                if (used < q.hints.size) {
                                    TextButton(
                                        onClick = {
                                            val h = hintsUsed.toMutableList()
                                            h[idx] = used + 1
                                            hintsUsed = h
                                        }
                                    ) {
                                        Text("💡 دریافت راهنما (${used + 1}/${q.hints.size}) [-0.5 نمره]", fontSize = 12.sp, color = Color(0xFFD97706))
                                    }
                                }
                                for (hIdx in 0 until used) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                            .background(Color(0xFFFEF3C7), RoundedCornerShape(6.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text("راهنما ${hIdx + 1}: ${q.hints[hIdx]}", color = Color(0xFF92400E), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { submitQuiz() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("ارسال و مشاهده کارنامه", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            } else {
                val analytics = remember { calculateAnalytics(results) }
                val totalEarned = results.sumOf { it.scoreEarned.toDouble() }.toFloat()
                val totalMax = results.sumOf { it.maxPossibleScore.toDouble() }.toFloat()
                val percent = if (totalMax > 0) ((totalEarned / totalMax) * 100).toInt() else 0

                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(studentName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                                Text("امتیاز نهایی: ${String.format(Locale.US, "%.1f", totalEarned)} از ${totalMax.toInt()} ($percent%)", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E3A8A), modifier = Modifier.padding(vertical = 4.dp))
                                Text("زمان صرف‌شده: ${totalTimeSpent} ثانیه", fontSize = 12.sp, color = Color(0xFF3B82F6))

                                Spacer(modifier = Modifier.height(14.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val file = MultiPagePdfManager.createPdfReport(context, studentName, results, analytics, totalTimeSpent)
                                            MultiPagePdfManager.printPdf(context, file, studentName)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                                    ) {
                                        Text("🖨️ چاپ فیزیکی", fontSize = 13.sp)
                                    }
                                    Button(
                                        onClick = {
                                            val file = MultiPagePdfManager.createPdfReport(context, studentName, results, analytics, totalTimeSpent)
                                            MultiPagePdfManager.sharePdf(context, file, studentName)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                    ) {
                                        Text("📄 اشتراک PDF", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("📊 ماتریس تسلط بر مباحث گرامری", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF0F172A))
                                Spacer(modifier = Modifier.height(10.dp))
                                analytics.forEach { a ->
                                    val color = when (a.level) {
                                        MasteryLevel.STRENGTH -> Color(0xFF22C55E)
                                        MasteryLevel.MODERATE -> Color(0xFFEAB308)
                                        MasteryLevel.WEAKNESS -> Color(0xFFEF4444)
                                    }
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(a.tag, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            Text("${String.format(Locale.US, "%.1f", a.earnedScore)}/${a.totalMaxScore.toInt()} (${a.percentage.toInt()}%)", color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(progress = a.percentage / 100f, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = Color(0xFFE2E8F0))
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(results) { idx, r ->
                        val bg = when (r.status) {
                            AnswerStatus.EXACT -> Color(0xFFDCFCE7)
                            AnswerStatus.TYPO -> Color(0xFFFEF9C3)
                            AnswerStatus.WRONG -> Color(0xFFFEE2E2)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            colors = CardDefaults.cardColors(containerColor = bg)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${idx + 1}. ${r.prompt}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("پاسخ شما: ${if (r.userAnswer.isBlank()) "(بدون پاسخ)" else r.userAnswer}  |  قطعیت: ${r.confidence.label}", fontSize = 12.sp, color = Color(0xFF334155), modifier = Modifier.padding(vertical = 2.dp))
                                Text("امتیاز کسب‌شده: ${r.scoreEarned}  |  راهنماهای استفاده‌شده: ${r.hintsUsedCount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                                if (r.status == AnswerStatus.WRONG) {
                                    Text("پاسخ صحیح: ${r.expected}", color = Color(0xFF991B1B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("قاعده: ${r.explanation}", color = Color(0xFF475569), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
