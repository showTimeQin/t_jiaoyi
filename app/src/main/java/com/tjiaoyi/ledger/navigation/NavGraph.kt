package com.tjiaoyi.ledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.ui.screens.AccountManageScreen
import com.tjiaoyi.ledger.ui.screens.AddStockScreen
import com.tjiaoyi.ledger.ui.screens.AddTradeScreen
import com.tjiaoyi.ledger.ui.screens.DataTransferScreen
import com.tjiaoyi.ledger.ui.screens.FeeSettingsScreen
import com.tjiaoyi.ledger.ui.screens.FxRateScreen
import com.tjiaoyi.ledger.ui.screens.HomeScreen
import com.tjiaoyi.ledger.ui.screens.StatisticsScreen
import com.tjiaoyi.ledger.ui.screens.StockDetailScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")

    object StockDetail : Screen("stock/{stockId}") {
        fun createRoute(stockId: Long) = "stock/$stockId"
    }

    /** 总账户的合并详情：传组里任意一只股票的 id，页面自己按代码把整组找出来 */
    object GroupDetail : Screen("stock_group/{stockId}") {
        fun createRoute(stockId: Long) = "stock_group/$stockId"
    }

    object AddStock : Screen("add_stock")

    object EditStock : Screen("edit_stock/{stockId}") {
        fun createRoute(stockId: Long) = "edit_stock/$stockId"
    }

    object AddTrade : Screen("add_trade/{stockId}") {
        fun createRoute(stockId: Long) = "add_trade/$stockId"
    }

    object EditTrade : Screen("edit_trade/{tradeId}") {
        fun createRoute(tradeId: Long) = "edit_trade/$tradeId"
    }

    /** stockId 传 0 表示统计当前账户的全部股票；group=true 表示统计同代码的整组股票 */
    object Statistics : Screen("statistics?stockId={stockId}&group={group}") {
        fun createRoute(stockId: Long = 0L, group: Boolean = false) =
            "statistics?stockId=$stockId&group=$group"
    }

    object DataTransfer : Screen("data_transfer")

    object Accounts : Screen("accounts")

    object FeeSettings : Screen("fee_settings")

    object FxRates : Screen("fx_rates")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    databaseHelper: DatabaseHelper
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                databaseHelper = databaseHelper,
                onStockClick = { navController.navigate(Screen.StockDetail.createRoute(it)) },
                onGroupClick = { navController.navigate(Screen.GroupDetail.createRoute(it)) },
                onAddStockClick = { navController.navigate(Screen.AddStock.route) },
                onEditStockClick = { navController.navigate(Screen.EditStock.createRoute(it)) },
                onStatisticsClick = { navController.navigate(Screen.Statistics.createRoute()) },
                onDataTransferClick = { navController.navigate(Screen.DataTransfer.route) },
                onAccountsClick = { navController.navigate(Screen.Accounts.route) },
                onFeeSettingsClick = { navController.navigate(Screen.FeeSettings.route) },
                onFxRatesClick = { navController.navigate(Screen.FxRates.route) }
            )
        }

        composable(
            route = Screen.StockDetail.route,
            arguments = listOf(navArgument("stockId") { type = NavType.LongType })
        ) { backStackEntry ->
            val stockId = backStackEntry.arguments?.getLong("stockId") ?: return@composable
            StockDetailScreen(
                stockId = stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() },
                onEditStockClick = { navController.navigate(Screen.EditStock.createRoute(it)) },
                onAddTradeClick = { navController.navigate(Screen.AddTrade.createRoute(it)) },
                onEditTradeClick = { navController.navigate(Screen.EditTrade.createRoute(it)) },
                onStatisticsClick = { navController.navigate(Screen.Statistics.createRoute(it)) }
            )
        }

        composable(
            route = Screen.GroupDetail.route,
            arguments = listOf(navArgument("stockId") { type = NavType.LongType })
        ) { backStackEntry ->
            val stockId = backStackEntry.arguments?.getLong("stockId") ?: return@composable
            StockDetailScreen(
                stockId = stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() },
                // 总账户是只读汇总视图，下面三个入口不会在界面上出现
                onEditStockClick = {},
                onAddTradeClick = {},
                onEditTradeClick = {},
                onStatisticsClick = { navController.navigate(Screen.Statistics.createRoute(it, group = true)) },
                groupMode = true
            )
        }

        composable(Screen.AddStock.route) {
            AddStockScreen(
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.EditStock.route,
            arguments = listOf(navArgument("stockId") { type = NavType.LongType })
        ) { backStackEntry ->
            val stockId = backStackEntry.arguments?.getLong("stockId") ?: return@composable
            AddStockScreen(
                stockId = stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AddTrade.route,
            arguments = listOf(navArgument("stockId") { type = NavType.LongType })
        ) { backStackEntry ->
            val stockId = backStackEntry.arguments?.getLong("stockId") ?: return@composable
            AddTradeScreen(
                stockId = stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.EditTrade.route,
            arguments = listOf(navArgument("tradeId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tradeId = backStackEntry.arguments?.getLong("tradeId") ?: return@composable
            val trade = databaseHelper.getTradeById(tradeId)
            if (trade == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            AddTradeScreen(
                stockId = trade.stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() },
                tradeId = tradeId
            )
        }

        composable(
            route = Screen.Statistics.route,
            arguments = listOf(
                navArgument("stockId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("group") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val stockId = backStackEntry.arguments?.getLong("stockId") ?: 0L
            val groupMode = backStackEntry.arguments?.getBoolean("group") ?: false
            StatisticsScreen(
                stockId = stockId,
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() },
                groupMode = groupMode
            )
        }

        composable(Screen.DataTransfer.route) {
            DataTransferScreen(
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Accounts.route) {
            AccountManageScreen(
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.FeeSettings.route) {
            FeeSettingsScreen(
                onBackClick = { navController.popBackStack() },
                onFxRatesClick = { navController.navigate(Screen.FxRates.route) }
            )
        }

        composable(Screen.FxRates.route) {
            FxRateScreen(
                databaseHelper = databaseHelper,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
