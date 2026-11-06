package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID
import com.tjiaoyi.ledger.data.model.Account
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.StockType
import com.tjiaoyi.ledger.data.model.detectStockType
import com.tjiaoyi.ledger.data.model.nowStamp

/**
 * 添加 / 编辑股票。
 *
 * 两件事比旧版多：选股票类型（A股 / 港股通，决定费率和建议卖出价的目标收益率），
 * 以及选归属的分账户。代码在同一账户内不允许重复，不同账户可以各建一只同代码股票，
 * 总账户会把它们合并成一张卡汇总。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStockScreen(
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit,
    stockId: Long = 0L
) {
    val editing = stockId > 0L
    val existing = remember(stockId) {
        if (editing) databaseHelper.getStockById(stockId) else null
    }

    var accounts by remember { mutableStateOf(databaseHelper.getAllAccounts()) }
    LaunchedEffect(AppState.accountsVersion) {
        accounts = databaseHelper.getAllAccounts()
    }

    val defaultAccountId = remember { databaseHelper.defaultAccountId() }
    // 从某个分账户进来时默认建到该账户；从总账户进来时建到默认账户，都可以手动改。
    var accountId by remember {
        mutableStateOf(
            existing?.accountId
                ?: if (AppState.selectedAccountId != ALL_ACCOUNTS_ID) {
                    AppState.selectedAccountId
                } else {
                    defaultAccountId
                }
        )
    }
    var accountMenuOpen by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var code by remember { mutableStateOf(existing?.code ?: "") }
    var stockType by remember { mutableStateOf(existing?.stockType ?: StockType.A_SHARE) }
    // 用户手点过类型之后，就不再跟着代码自动改，免得把他选的类型覆盖掉
    var typeTouched by remember { mutableStateOf(editing) }

    val trimmedName = name.trim()
    val trimmedCode = code.trim()

    LaunchedEffect(trimmedCode) {
        if (!typeTouched) stockType = detectStockType(trimmedCode)
    }

    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val codeTaken = trimmedCode.isNotEmpty() &&
        databaseHelper.isCodeTaken(trimmedCode, accountId, stockId)
    val canSave = trimmedName.isNotEmpty() && !codeTaken

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing) "编辑股票" else "添加股票",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("股票名称") },
                placeholder = { Text("例如: 腾讯控股") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("股票代码") },
                placeholder = { Text("例如: 00700 / 600519") },
                isError = codeTaken,
                supportingText = {
                    if (codeTaken) {
                        Text("这个账户里已经添加过这个代码了")
                    } else {
                        Text("5 位数字自动识别为港股通，6 位数字识别为 A股；可以不填但不能重复")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "股票类型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StockType.entries.forEach { type ->
                    FilterChip(
                        selected = stockType == type,
                        onClick = {
                            stockType = type
                            typeTouched = true
                        },
                        label = { Text(type.label) }
                    )
                }
            }
            Text(
                text = "决定手续费费率和建议卖出价的目标到手收益率：" +
                    "A股 ${AppState.feeConfig.targetLabel(StockType.A_SHARE)}、" +
                    "港股通 ${AppState.feeConfig.targetLabel(StockType.HK_CONNECT)}。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "归属账户",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { accountMenuOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedAccount?.name ?: "默认账户",
                        modifier = Modifier.weight(1f)
                    )
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "选择账户")
                }
                DropdownMenu(
                    expanded = accountMenuOpen,
                    onDismissRequest = { accountMenuOpen = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name + if (account.isDefault) "（默认）" else "") },
                            onClick = {
                                accountId = account.id
                                accountMenuOpen = false
                            }
                        )
                    }
                }
            }
            Text(
                text = if (editing) {
                    "改归属账户会连同这只股票的全部做T记录一起移动，数据不会丢。"
                } else {
                    "不同账户可以各建一只相同代码的股票，总账户会把它们合并汇总。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (!canSave) return@Button
                    // 账户可能在别的页面被删掉了，落到默认账户，别写入一个不存在的 id
                    val targetAccountId =
                        if (accounts.any { it.id == accountId }) accountId else defaultAccountId
                    val stock = Stock(
                        id = if (editing) stockId else 0L,
                        name = trimmedName,
                        code = trimmedCode,
                        accountId = targetAccountId,
                        stockType = stockType,
                        sortOrder = existing?.sortOrder
                            ?: databaseHelper.nextSortOrder(targetAccountId),
                        createdAt = existing?.createdAt ?: nowStamp()
                    )
                    if (editing) {
                        databaseHelper.updateStock(stock)
                    } else {
                        databaseHelper.insertStock(stock)
                    }
                    onBackClick()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave
            ) {
                Text(text = if (editing) "保存修改" else "创建股票")
            }
        }
    }
}
