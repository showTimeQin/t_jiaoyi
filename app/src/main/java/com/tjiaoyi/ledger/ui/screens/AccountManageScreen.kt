package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.Account
import com.tjiaoyi.ledger.data.model.AppState

/**
 * 分账户管理。
 *
 * 「总账户」不是这里的一行数据，它是首页把各分账户按股票代码合并后的只读视图。
 * 删除分账户不会删股票：该账户下的股票会整体挪到默认账户，做T记录一条都不会丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManageScreen(
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit
) {
    var accounts by remember { mutableStateOf(databaseHelper.getAllAccounts()) }
    var nameDialog by remember { mutableStateOf<Pair<Boolean, Account?>?>(null) } // true=新增 / false=改名
    var nameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }

    fun refresh() {
        accounts = databaseHelper.getAllAccounts()
        AppState.notifyAccountsChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分账户管理", style = MaterialTheme.typography.headlineMedium) },
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
        },
        floatingActionButton = {
            Button(onClick = {
                nameInput = ""
                nameDialog = true to null
            }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加账户")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "hint") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "每只股票归属一个分账户，它的做T记录也跟着归属。" +
                            "首页的「总账户」会把所有账户里同代码的股票合并成一张卡，只用于汇总查看。\n" +
                            "删除账户时，账户下的股票会自动挪到默认账户，不会删除做T记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            items(accounts, key = { it.id }) { account ->
                AccountRow(
                    account = account,
                    stockCount = databaseHelper.countStocksByAccount(account.id),
                    onRename = {
                        nameInput = account.name
                        nameDialog = false to account
                    },
                    onDelete = { deleteTarget = account }
                )
            }

            item(key = "bottom_space") { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    // ---------------------------------------------------------------- 新增 / 改名
    nameDialog?.let { (isNew, target) ->
        val trimmed = nameInput.trim()
        val duplicated = accounts.any { it.name.equals(trimmed, ignoreCase = true) && it.id != target?.id }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (isNew) "添加分账户" else "修改账户名称") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("账户名称") },
                        placeholder = { Text("例如: 主账户 / 港股账户") },
                        singleLine = true,
                        isError = trimmed.isNotEmpty() && duplicated,
                        supportingText = {
                            Text(
                                if (trimmed.isNotEmpty() && duplicated) "已经有同名账户了"
                                else "名字只用来区分，随时可以改"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (trimmed.isEmpty() || duplicated) return@TextButton
                        if (isNew) databaseHelper.insertAccount(trimmed)
                        else target?.let { databaseHelper.updateAccount(it.copy(name = trimmed)) }
                        nameDialog = null
                        refresh()
                    },
                    enabled = trimmed.isNotEmpty() && !duplicated
                ) { Text(if (isNew) "添加" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { nameDialog = null }) { Text("取消") }
            }
        )
    }

    // ---------------------------------------------------------------- 删除确认
    deleteTarget?.let { target ->
        val count = databaseHelper.countStocksByAccount(target.id)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除账户") },
            text = {
                Text(
                    "确定删除「${target.name}」吗？" +
                        if (count > 0) {
                            "它名下的 $count 只股票会挪到默认账户，做T记录全部保留。"
                        } else {
                            "该账户下还没有股票。"
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (databaseHelper.deleteAccount(target.id)) {
                        // 如果当前正停在被删除的账户上，回到总账户，避免选中一个不存在的账户
                        if (AppState.selectedAccountId == target.id) {
                            AppState.selectAccount(com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID)
                        }
                    }
                    deleteTarget = null
                    refresh()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    stockCount: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (account.isDefault) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "$stockCount 只股票",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "改名",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = !account.isDefault) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除账户",
                    tint = if (account.isDefault) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}
