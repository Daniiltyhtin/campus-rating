package ru.campusrating.ledger.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.ledger.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceRulesTest {
    @ParameterizedTest
    @CsvSource({
            "AR,900,200,100", "AR,1000,200,0", "AR,10,-20,-10", "AR,0,-20,0",
            "AR,500,200,200", "AR,500,-20,-20", "AR,800,200,200", "AR,20,-20,-20",
            "AR,0,1000,1000", "AR,1000,-1000,-1000", "AR,500,2147483647,500",
            "AR,500,-2147483648,-500", "BOA,5000,50,50", "BUA,5000,50,50"
    })
    void computesActualAmount(Currency currency, int balance, int nominal, int actual) {
        assertThat(BalanceRules.actualAmount(currency, balance, nominal)).isEqualTo(actual);
    }
}
