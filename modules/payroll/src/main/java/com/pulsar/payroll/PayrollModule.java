package com.pulsar.payroll;

import com.pulsar.kernel.module.ModuleDefinition;
import com.pulsar.kernel.module.ModuleManifest;
import org.springframework.stereotype.Component;

@Component
public class PayrollModule implements ModuleDefinition {
    @Override public String id() { return "payroll"; }
    @Override public ModuleManifest manifest() {
        return new ModuleManifest("payroll", "Payroll", "Run payroll and issue payslips", "💰", "finance");
    }
}
