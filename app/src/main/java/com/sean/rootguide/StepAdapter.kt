package com.sean.rootguide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

/**
 * 步骤列表，按「必装 / 选装」分两个大板块展示（各带一个分组标题行）。
 * 板块内保持 PlanBuilder 给出的原始顺序；必装在前，选装在后。
 */
class StepAdapter(
    private val steps: List<Step>,
    private val done: MutableSet<String>,
    /** stepId -> 安装过程/结果文本，由 Activity 维护，Adapter 只负责显示 */
    private val statuses: MutableMap<String, String>,
    /** 正在执行的 stepId，用于禁用按钮防重复点击 */
    private val busy: MutableSet<String>,
    /** 已装模块目录名，用于在卡片上标「已装」 */
    private val installedModules: () -> List<String>,
    private val onInstall: (Step) -> Unit,
    private val onChange: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_STEP = 1
    }

    /** 展示项：分组标题，或一条步骤 */
    sealed interface Item {
        data class Header(val required: Boolean, val count: Int) : Item
        data class StepItem(val step: Step) : Item
    }

    private val items: List<Item> = buildList {
        val required = steps.filter { it.required }
        val optional = steps.filter { !it.required }
        if (required.isNotEmpty()) {
            add(Item.Header(true, required.size))
            required.forEach { add(Item.StepItem(it)) }
        }
        if (optional.isNotEmpty()) {
            add(Item.Header(false, optional.size))
            optional.forEach { add(Item.StepItem(it)) }
        }
    }

    /** stepId -> 展示列表中的位置（仅步骤项） */
    private val stepPositions: Map<String, Int> =
        items.mapIndexedNotNull { idx, item ->
            (item as? Item.StepItem)?.step?.id?.let { it to idx }
        }.toMap()

    fun doneSet(): Set<String> = done.toSet()

    /** 安装成功后由 Activity 调用，直接改内部集合，避免两份状态不一致 */
    fun markDone(stepId: String) {
        done.add(stepId)
        refresh(stepId)
    }

    fun clearAll() {
        done.clear()
        notifyDataSetChanged()
    }

    fun refresh(stepId: String) {
        stepPositions[stepId]?.let { notifyItemChanged(it) }
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is Item.Header) TYPE_HEADER else TYPE_STEP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_group_header, parent, false))
        } else {
            StepViewHolder(inflater.inflate(R.layout.item_step, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderViewHolder).bind(item)
            is Item.StepItem -> (holder as StepViewHolder).bind(item.step)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvGroupTitle)
        private val tvHint: TextView = itemView.findViewById(R.id.tvGroupHint)

        fun bind(header: Item.Header) {
            val ctx = itemView.context
            tvTitle.text = ctx.getString(
                if (header.required) R.string.group_required else R.string.group_optional,
                header.count
            )
            tvTitle.setTextColor(ctx.getColor(if (header.required) R.color.required else R.color.optional))
            tvHint.text = if (header.required) "按顺序" else "按需添加"
        }
    }

    inner class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val cbDone: CheckBox = itemView.findViewById(R.id.cbDone)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
        private val tvKind: TextView = itemView.findViewById(R.id.tvKind)
        private val tvInstalled: TextView = itemView.findViewById(R.id.tvInstalled)
        private val tvDesc: TextView = itemView.findViewById(R.id.tvDesc)
        private val tvVerify: TextView = itemView.findViewById(R.id.tvVerify)
        private val tvWarn: TextView = itemView.findViewById(R.id.tvWarn)
        private val warnBox: View = itemView.findViewById(R.id.warnBox)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnInstall: Button = itemView.findViewById(R.id.btnInstall)
        private val btnDownload: Button = itemView.findViewById(R.id.btnDownload)
        private val btnCopy: Button = itemView.findViewById(R.id.btnCopy)

        fun bind(step: Step) {
            val ctx = itemView.context

            tvTitle.text = step.title
            tvDesc.text = step.desc
            tvBadge.text = if (step.required) "必装" else "可选"
            tvBadge.setTextColor(ctx.getColor(if (step.required) R.color.required else R.color.optional))

            tvKind.text = when (step.kind) {
                StepKind.SETUP -> "设置项"
                StepKind.MODULE -> when {
                    // 没有分发渠道的模块（KPM / SUSFS）如实标「手动」，不要伪装成可一键装
                    step.module == null -> "模块 · 手动获取"
                    step.module.kind == ArtifactKind.APK -> "插件 · APK"
                    else -> "模块 · ZIP"
                }
            }

            val installed = ModuleCatalog.isInstalled(step.module, installedModules())
            tvInstalled.visibility = if (installed) View.VISIBLE else View.GONE
            tvInstalled.text = "● 已装"

            tvVerify.visibility = if (step.verify.isNullOrBlank()) View.GONE else View.VISIBLE
            tvVerify.text = step.verify?.let { "验证：$it" } ?: ""

            warnBox.visibility = if (step.warn.isNullOrBlank()) View.GONE else View.VISIBLE
            tvWarn.text = step.warn ?: ""

            val status = statuses[step.id]
            tvStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
            tvStatus.text = status ?: ""

            // 「下载并安装」：只有带 module 的步骤才有
            if (step.module == null) {
                btnInstall.visibility = View.GONE
            } else {
                btnInstall.visibility = View.VISIBLE
                val running = busy.contains(step.id)
                btnInstall.isEnabled = !running
                btnInstall.text = when {
                    running -> "处理中…"
                    installed -> "重新安装"
                    else -> ctx.getString(R.string.download_and_install)
                }
                btnInstall.setOnClickListener { if (!busy.contains(step.id)) onInstall(step) }
            }

            // 「仓库」：跳浏览器
            btnDownload.visibility = if (step.url.isNullOrBlank()) View.GONE else View.VISIBLE
            btnDownload.setOnClickListener {
                step.url?.let { url ->
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }

            // 「命令」：复制
            btnCopy.visibility = if (step.cmd.isNullOrBlank()) View.GONE else View.VISIBLE
            btnCopy.setOnClickListener {
                step.cmd?.let { cmd ->
                    val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }

            cbDone.setOnCheckedChangeListener(null)
            cbDone.isChecked = done.contains(step.id)
            cbDone.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) done.add(step.id) else done.remove(step.id)
                onChange()
            }
        }
    }
}
