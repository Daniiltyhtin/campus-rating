package ru.campusrating.ledger.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.ReconciliationResult;

@RestController
class LedgerReconciliationController {
    private final LedgerApi ledger;

    LedgerReconciliationController(LedgerApi ledger) { this.ledger = ledger; }

    @GetMapping("/api/v1/admin/ledger/reconciliation")
    public ReconciliationResult reconcileLedger() { return ledger.reconcile(); }
}
