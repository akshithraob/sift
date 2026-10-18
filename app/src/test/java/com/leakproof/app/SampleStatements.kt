package com.leakproof.app

/** Synthetic bank statement lines (no real data) used by the parser tests. */
object SampleStatements {
    fun bankLines(bankName: String): List<String> {
        return when (bankName.uppercase()) {
            "SBI" -> listOf(
                "Txn Date, Narration, Ref No, Debit, Credit, Balance",
                "01/08/2026, TRANSFER TO 48219012345/UPI/NETFLIX/429182910/UPI, 429182910, 649.00, , 24500.00",
                "01/09/2026, TRANSFER TO 48219012345/UPI/NETFLIX/581920191/UPI, 581920191, 649.00, , 23851.00",
                "01/10/2026, TRANSFER TO 48219012345/UPI/NETFLIX/691028491/UPI, 691028491, 649.00, , 23202.00",
                "10/08/2026, ACH D-ZERODHA BROKING LTD-482109, ACH90124, 5000.00, , 18202.00",
                "10/09/2026, ACH D-ZERODHA BROKING LTD-482109, ACH98412, 5000.00, , 13202.00",
                "10/10/2026, ACH D-ZERODHA BROKING LTD-482109, ACH10294, 5000.00, , 8202.00"
            )
            "HDFC" -> listOf(
                "Date, Narration, Chq/Ref No, Value Dt, Withdrawal Amt., Deposit Amt., Closing Balance",
                "15/08/26, UPI-SWIGGY-swiggy@icici-HDFC000123-429182910-UPI, 429182910, 15/08/26, 420.00, , 15000.00",
                "10/08/26, ACH D-GROWW INVEST TECH-481921, 9812401, 10/08/26, 2000.00, , 13000.00",
                "10/09/26, ACH D-GROWW INVEST TECH-481921, 9841092, 10/09/26, 2000.00, , 11000.00",
                "10/10/26, ACH D-GROWW INVEST TECH-481921, 9918294, 10/10/26, 2000.00, , 9000.00",
                "02/08/26, POS 482190 ZOMATO GOLD MUMBAI IN, POS9812, 02/08/26, 999.00, , 8001.00"
            )
            "ICICI" -> listOf(
                "Transaction Date, Value Date, Cheque Number, Transaction Remarks, Withdrawal Amount (INR ), Deposit Amount (INR ), Balance (INR )",
                "20-08-2026, 20-08-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/5928192, 119.00, 0.00, 50000.00",
                "20-09-2026, 20-09-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/6829102, 119.00, 0.00, 49881.00",
                "20-10-2026, 20-10-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/7918201, 119.00, 0.00, 49762.00",
                "05-08-2026, 05-08-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1850.00, 0.00, 47912.00",
                "05-09-2026, 05-09-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1910.00, 0.00, 46002.00",
                "05-10-2026, 05-10-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1880.00, 0.00, 44122.00"
            )
            "AXIS" -> listOf(
                "Tran Date, Value Date, Transaction Details, Chq No, Debit, Credit, Balance",
                "12-08-2026, 12-08-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/98124, -, 1499.00, , 32000.00",
                "12-09-2026, 12-09-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/98912, -, 1499.00, , 30501.00",
                "12-10-2026, 12-10-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/99481, -, 1499.00, , 29002.00",
                "08-10-2026, 08-10-2026, UPI-MANDATE-HOTSTAR-hotstar@axis/9281948291, -, 1499.00, , 27503.00"
            )
            else -> listOf(
                "10/08/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH90124",
                "10/09/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH98412",
                "10/10/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH10294",
                "02/08/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1850.00, DR, SI48291",
                "02/09/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1920.00, DR, SI59281",
                "02/10/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1890.00, DR, SI70129",
                "15/08/2026, UPI-MANDATE-DISNEY HOTSTAR-MND981, 1499.00, DR, UPI2849"
            )
        }
    }
}
