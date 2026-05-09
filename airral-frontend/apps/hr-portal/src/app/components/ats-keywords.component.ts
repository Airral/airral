import { Component, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-ats-keywords',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="ats-keywords">
      <div class="header">
        <h3>ATS Keywords</h3>
        <p class="help-text">
          Add keywords to automatically filter applicants.
          @if (isProfessionalTier()) {
            Set weights for AI-powered matching.
          }
        </p>
      </div>

      <!-- Input for new keyword -->
      <div class="keyword-input">
        <input
          type="text"
          [(ngModel)]="newKeyword"
          (keyup.enter)="addKeyword()"
          placeholder="e.g., Python, 5 years experience, AWS..."
          class="keyword-field"
        />
        <button
          (click)="addKeyword()"
          class="add-btn"
          [disabled]="!newKeyword().trim()"
        >
          + Add
        </button>
      </div>

      <!-- Keyword list with weights -->
      <div class="keywords-list">
        @if (keywords().length === 0) {
          <div class="empty-state">
            <p>No keywords added yet</p>
            <small>Add keywords to enable ATS filtering</small>
          </div>
        } @else {
          @for (keyword of keywords(); track keyword) {
            <div class="keyword-item">
              <div class="keyword-content">
                <span class="keyword-text">{{ keyword }}</span>
                @if (isProfessionalTier()) {
                  <div class="weight-control">
                    <label>Weight:</label>
                    <select
                      [value]="getWeight(keyword)"
                      (change)="setWeight(keyword, $any($event.target).value)"
                      class="weight-select"
                    >
                      <option value="1">Normal</option>
                      <option value="1.5">Important</option>
                      <option value="2">Critical</option>
                    </select>
                  </div>
                }
              </div>
              <button
                (click)="removeKeyword(keyword)"
                class="remove-btn"
                aria-label="Remove keyword"
              >
                ×
              </button>
            </div>
          }
        }
      </div>

      <!-- ATS threshold setting -->
      <div class="threshold-setting">
        <label for="ats-threshold">Minimum Match Score:</label>
        <div class="threshold-input">
          <input
            id="ats-threshold"
            type="range"
            min="0"
            max="100"
            step="5"
            [value]="minScore()"
            (input)="onMinScoreChange($any($event.target).value)"
            class="threshold-slider"
          />
          <span class="threshold-value">{{ minScore() }}%</span>
        </div>
        <small class="help-text">
          Applicants scoring below {{ minScore() }}% will be stored but not shown in your default view
        </small>
      </div>

      <!-- Summary -->
      @if (keywords().length > 0) {
        <div class="summary">
          <strong>{{ keywords().length }}</strong> keywords configured
          • Minimum score: <strong>{{ minScore() }}%</strong>
        </div>
      }
    </div>
  `,
  styles: [`
    .ats-keywords {
      background: white;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 20px;
      margin-bottom: 20px;
    }

    .header h3 {
      margin: 0 0 8px 0;
      font-size: 18px;
      font-weight: 600;
    }

    .help-text {
      margin: 0 0 16px 0;
      font-size: 14px;
      color: #6b7280;
    }

    .keyword-input {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
    }

    .keyword-field {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid #d1d5db;
      border-radius: 6px;
      font-size: 14px;
    }

    .keyword-field:focus {
      outline: none;
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
    }

    .add-btn {
      padding: 8px 16px;
      background: #3b82f6;
      color: white;
      border: none;
      border-radius: 6px;
      font-weight: 500;
      cursor: pointer;
    }

    .add-btn:hover:not(:disabled) {
      background: #2563eb;
    }

    .add-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .keywords-list {
      margin-bottom: 20px;
    }

    .empty-state {
      text-align: center;
      padding: 32px;
      color: #9ca3af;
    }

    .empty-state small {
      display: block;
      margin-top: 4px;
      font-size: 12px;
    }

    .keyword-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px;
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      margin-bottom: 8px;
      background: #f9fafb;
    }

    .keyword-content {
      display: flex;
      align-items: center;
      gap: 16px;
      flex: 1;
    }

    .keyword-text {
      font-size: 14px;
      font-weight: 500;
    }

    .weight-control {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: #6b7280;
    }

    .weight-select {
      padding: 4px 8px;
      border: 1px solid #d1d5db;
      border-radius: 4px;
      font-size: 13px;
    }

    .remove-btn {
      padding: 4px 8px;
      background: #ef4444;
      color: white;
      border: none;
      border-radius: 4px;
      font-size: 20px;
      line-height: 1;
      cursor: pointer;
      width: 28px;
      height: 28px;
    }

    .remove-btn:hover {
      background: #dc2626;
    }

    .threshold-setting {
      margin-top: 20px;
      padding-top: 20px;
      border-top: 1px solid #e5e7eb;
    }

    .threshold-setting label {
      display: block;
      font-size: 14px;
      font-weight: 500;
      margin-bottom: 8px;
    }

    .threshold-input {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }

    .threshold-slider {
      flex: 1;
      height: 6px;
    }

    .threshold-value {
      font-weight: 600;
      min-width: 50px;
      text-align: right;
    }

    .summary {
      margin-top: 16px;
      padding: 12px;
      background: #eff6ff;
      border: 1px solid #bfdbfe;
      border-radius: 6px;
      font-size: 14px;
      color: #1e40af;
    }
  `]
})
export class AtsKeywordsComponent {
  // Inputs
  keywords = input<string[]>([]);
  weights = input<Record<string, number>>({});
  minScore = input<number>(70);
  isProfessionalTier = input<boolean>(false);

  // Outputs
  keywordsChange = output<string[]>();
  weightsChange = output<Record<string, number>>();
  minScoreChange = output<number>();

  // Local state
  newKeyword = signal('');

  addKeyword() {
    const keyword = this.newKeyword().trim();
    if (!keyword) return;

    const currentKeywords = this.keywords();
    if (currentKeywords.includes(keyword)) {
      alert('Keyword already added');
      return;
    }

    this.keywordsChange.emit([...currentKeywords, keyword]);
    this.newKeyword.set('');
  }

  removeKeyword(keyword: string) {
    const updated = this.keywords().filter(k => k !== keyword);
    this.keywordsChange.emit(updated);

    // Also remove weight if exists
    const updatedWeights = { ...this.weights() };
    delete updatedWeights[keyword];
    this.weightsChange.emit(updatedWeights);
  }

  getWeight(keyword: string): number {
    return this.weights()[keyword] || 1;
  }

  setWeight(keyword: string, weight: string) {
    const updatedWeights = {
      ...this.weights(),
      [keyword]: parseFloat(weight)
    };
    this.weightsChange.emit(updatedWeights);
  }

  onMinScoreChange(value: string) {
    this.minScoreChange.emit(parseInt(value, 10));
  }
}
