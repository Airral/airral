import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { TierGuardService } from '@airral/shared-utils';

interface BenefitPlan {
  name: string;
  provider: string;
  coverage: string;
  status: string;
  premium: number;
  employerContribution: number;
}

interface RetirementPlan {
  plan: string;
  employeeContribution: number;
  employerMatch: number;
  currentBalance: number;
  ytdContributions: number;
}

interface PTOBalance {
  type: string;
  accrued: number;
  used: number;
  balance: number;
}

interface EmployeeBenefits {
  healthInsurance: BenefitPlan;
  dentalInsurance: BenefitPlan;
  visionInsurance: BenefitPlan;
  retirement: RetirementPlan;
  ptoBalances: PTOBalance[];
  perks: string[];
}

@Component({
  selector: 'app-employee-benefits',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './benefits.component.html',
  styleUrl: './benefits.component.css',
})
export class BenefitsComponent implements OnInit {
  protected readonly tierGuard = inject(TierGuardService);

  benefits: EmployeeBenefits | null = null;
  loading = true;
  error: string | null = null;

  ngOnInit(): void {
    this.loadBenefits();
  }

  loadBenefits(): void {
    this.error = null;
    this.benefits = null;
    this.loading = false;
  }

  get isPro(): boolean {
    return this.tierGuard.isProTier;
  }
}
