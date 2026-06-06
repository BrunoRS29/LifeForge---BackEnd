# Análise de sensibilidade — Motor de Monte Carlo

> Artefato gerado por `EngineAnalysisTest`. **Baseline**: capital R$ 50.000, aporte R$ 2.000/mês, retorno 8% a.a., volatilidade 15% a.a., inflação 4% a.a., horizonte 240 meses (20 anos), meta R$ 1.000.000, 20.000 simulações, seed fixa (42).

Metodologia *one-at-a-time*: cada tabela varia UM parâmetro e mantém os demais no baseline. Métrica principal: probabilidade de atingir a meta.

## Retorno esperado (a.a.)

| Retorno esperado (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 4% | 25.6% | R$ 748,250 | R$ 439,295 | R$ 1,338,851 |
| 6% | 45.2% | R$ 946,882 | R$ 541,709 | R$ 1,733,637 |
| 8% | 65.7% | R$ 1,206,510 | R$ 673,806 | R$ 2,258,196 |
| 10% | 82.1% | R$ 1,549,911 | R$ 844,837 | R$ 2,956,677 |
| 12% | 92.1% | R$ 2,002,227 | R$ 1,068,492 | R$ 3,881,757 |

## Volatilidade (a.a.)

| Volatilidade (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 5% | 96.9% | R$ 1,351,673 | R$ 1,101,995 | R$ 1,664,821 |
| 10% | 79.4% | R$ 1,294,493 | R$ 867,969 | R$ 1,968,128 |
| 15% | 65.7% | R$ 1,206,510 | R$ 673,806 | R$ 2,258,196 |
| 20% | 55.9% | R$ 1,096,389 | R$ 517,210 | R$ 2,516,985 |
| 30% | 42.3% | R$ 845,346 | R$ 301,530 | R$ 2,850,182 |

## Aporte mensal

| Aporte mensal | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| R$ 1000 | 23.5% | R$ 698,260 | R$ 379,056 | R$ 1,344,918 |
| R$ 1500 | 46.0% | R$ 952,423 | R$ 526,412 | R$ 1,799,383 |
| R$ 2000 | 65.7% | R$ 1,206,510 | R$ 673,806 | R$ 2,258,196 |
| R$ 2500 | 79.7% | R$ 1,461,765 | R$ 820,616 | R$ 2,723,564 |
| R$ 3000 | 88.6% | R$ 1,716,602 | R$ 968,356 | R$ 3,181,273 |

## Prob. desemprego (a.a.)

| Prob. desemprego (a.a.) | P(sucesso) | Mediana | P10 | P90 |
|---|---|---|---|---|
| 0% | 65.7% | R$ 1,206,510 | R$ 673,806 | R$ 2,258,196 |
| 5% | 63.9% | R$ 1,177,636 | R$ 654,789 | R$ 2,209,972 |
| 10% | 62.4% | R$ 1,157,512 | R$ 644,023 | R$ 2,159,790 |
| 20% | 59.0% | R$ 1,112,576 | R$ 616,398 | R$ 2,076,693 |

## Leitura

- **Retorno** e **aporte** elevam a probabilidade de sucesso (relação direta).
- **Volatilidade** e **desemprego** reduzem a probabilidade (relação inversa); a volatilidade ainda alarga a faixa P10–P90, ou seja, aumenta a incerteza.
- No baseline, o parâmetro de maior alavancagem é o **aporte mensal**.
