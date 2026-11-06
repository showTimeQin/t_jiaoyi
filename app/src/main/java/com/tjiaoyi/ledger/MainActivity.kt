package com.tjiaoyi.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.navigation.NavGraph
import com.tjiaoyi.ledger.ui.theme.TjLedgerTheme

class MainActivity : ComponentActivity() {
    private lateinit var databaseHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        databaseHelper = DatabaseHelper(this)
        // 费率配置从 SharedPreferences 读回来；账户固定从「总账户」开始。
        AppState.load(this)
        databaseHelper.ensureDefaultAccount()

        setContent {
            TjLedgerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        databaseHelper = databaseHelper
                    )
                }
            }
        }
    }
}
