/*
 * Portfolio
 * Copyright (C) 2020  Vitalii Ananev <an-vitek@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.portfolio.portfolio.view.excel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.portfolio.portfolio.converter.SecurityConverter;
import ru.portfolio.portfolio.converter.SecurityEventCashFlowConverter;
import ru.portfolio.portfolio.converter.TransactionConverter;
import ru.portfolio.portfolio.entity.SecurityEntity;
import ru.portfolio.portfolio.entity.SecurityEventCashFlowEntity;
import ru.portfolio.portfolio.entity.TransactionCashFlowEntity;
import ru.portfolio.portfolio.pojo.*;
import ru.portfolio.portfolio.repository.SecurityEventCashFlowRepository;
import ru.portfolio.portfolio.repository.SecurityRepository;
import ru.portfolio.portfolio.repository.TransactionCashFlowRepository;
import ru.portfolio.portfolio.repository.TransactionRepository;
import ru.portfolio.portfolio.view.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ru.portfolio.portfolio.view.excel.StockMarketProfitExcelTableHeader.*;

@Component
@RequiredArgsConstructor
public class StockMarketProfitExcelTableFactory implements TableFactory {
    // isin -> security price currency
    private final Map<String, String> securityCurrencies = new ConcurrentHashMap<>();
    private final TransactionRepository transactionRepository;
    private final SecurityRepository securityRepository;
    private final TransactionCashFlowRepository transactionCashFlowRepository;
    private final TransactionConverter transactionConverter;
    private final SecurityConverter securityConverter;
    private final PaidInterestFactory paidInterestFactory;
    private final SecurityEventCashFlowRepository securityEventCashFlowRepository;
    private final SecurityEventCashFlowConverter securityEventCashFlowConverter;
    private final ForeignExchangeRateService foreignExchangeRateService;

    public Table create(Portfolio portfolio) {
        return create(portfolio, getSecuritiesIsin(portfolio));
    }

    public Table create(Portfolio portfolio, String forCurrency) {
        return create(portfolio, getSecuritiesIsin(portfolio, forCurrency));
    }

    public Table create(Portfolio portfolio, Collection<String> securitiesIsin) {
        Table openPositionsProfit = new Table();
        Table closedPositionsProfit = new Table();
        for (String isin : securitiesIsin) {
            Optional<SecurityEntity> securityEntity = securityRepository.findByIsin(isin);
            if (securityEntity.isPresent()) {
                Security security = securityConverter.fromEntity(securityEntity.get());
                Positions positions = getPositions(portfolio, security);
                PaidInterest paidInterest = paidInterestFactory.create(portfolio.getId(), security, positions);
                openPositionsProfit.addAll(getPositionProfit(security, positions.getOpenedPositions(),
                        paidInterest, this::getOpenedPositionProfit));
                closedPositionsProfit.addAll(getPositionProfit(security, positions.getClosedPositions(),
                        paidInterest, this::getClosedPositionProfit));
                openPositionsProfit.addAll(getPositionProfit(security, paidInterest.getFictitiousPositions(),
                        paidInterest, this::getOpenedPositionProfit));
            }
        }
        Table profit = new Table();
        profit.addAll(openPositionsProfit);
        profit.addAll(closedPositionsProfit);
        return profit;
    }

    private Collection<String> getSecuritiesIsin(Portfolio portfolio) {
        List<String> securities = new ArrayList<>();
        securities.addAll(transactionRepository.findDistinctIsinByPortfolioOrderByTimestampDesc(portfolio));
        securities.addAll(transactionRepository.findDistinctFxInstrumentByPortfolioOrderByTimestampDesc(portfolio));
        return securities;
    }

    private Collection<String> getSecuritiesIsin(Portfolio portfolio, String currency) {
        List<String> securities = new ArrayList<>();
        securities.addAll(transactionRepository.findDistinctIsinByPortfolioAndCurrencyOrderByTimestampDesc(portfolio, currency));
        securities.addAll(transactionRepository.findDistinctFxInstrumentByPortfolioAndCurrencyOrderByTimestampDesc(portfolio, currency));
        return securities;
    }

    private Positions getPositions(Portfolio portfolio, Security security) {
        Deque<Transaction> transactions = transactionRepository
                .findBySecurityIsinAndPkPortfolioOrderByTimestampAscPkIdAsc(security.getIsin(), portfolio.getId())
                .stream()
                .map(transactionConverter::fromEntity)
                .collect(Collectors.toCollection(LinkedList::new));
        Deque<SecurityEventCashFlow> redemption = getRedemption(portfolio, security);
        return new Positions(transactions, redemption);
    }

    private Deque<SecurityEventCashFlow> getRedemption(Portfolio portfolio, Security securityEntity) {
        return securityEventCashFlowRepository
                .findByPortfolioIdAndSecurityIsinAndCashFlowTypeIdOrderByTimestampAsc(
                        portfolio.getId(),
                        securityEntity.getIsin(),
                        CashFlowType.REDEMPTION.getId())
                .stream()
                .map(securityEventCashFlowConverter::fromEntity)
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private <T extends Position> Table getPositionProfit(Security security,
                                                         Deque<T> positions,
                                                         PaidInterest paidInterest,
                                                         Function<T, Table.Record> profitBuilder) {
        Table rows = new Table();
        for (T position : positions) {
            Table.Record record = profitBuilder.apply(position);
            record.putAll(getPaidInterestProfit(position, paidInterest));
            record.put(SECURITY,
                    Optional.ofNullable(security.getName())
                            .orElse(security.getIsin()));
            rows.add(record);
        }
        return rows;
    }

    private Table.Record getOpenedPositionProfit(OpenedPosition position) {
        Table.Record row = new Table.Record();
        Transaction transaction = position.getOpenTransaction();
        row.put(OPEN_DATE, transaction.getTimestamp());
        row.put(COUNT, Math.abs(position.getCount()) * Integer.signum(transaction.getCount()));
        row.put(OPEN_PRICE, getTransactionCashFlow(transaction, CashFlowType.PRICE, 1d / transaction.getCount()));
        double multipier = Math.abs(1d * position.getCount() / transaction.getCount());
        row.put(OPEN_AMOUNT, getTransactionCashFlow(transaction, CashFlowType.PRICE, multipier));
        row.put(OPEN_ACCRUED_INTEREST, getTransactionCashFlow(transaction, CashFlowType.ACCRUED_INTEREST, multipier));
        row.put(OPEN_COMMISSION, getTransactionCashFlow(transaction, CashFlowType.COMMISSION, multipier));
        return row;
    }

    private Table.Record getClosedPositionProfit(ClosedPosition position) {
        // open transaction info
        Table.Record row = new Table.Record(getOpenedPositionProfit(position));
        // close transaction info
        Transaction transaction = position.getCloseTransaction();
        double multipier = Math.abs(1d * position.getCount() / transaction.getCount());
        row.put(CLOSE_DATE, transaction.getTimestamp());
        BigDecimal closeAmount;
        switch (position.getClosingEvent()) {
            case PRICE:
                closeAmount = getTransactionCashFlow(transaction, CashFlowType.PRICE, multipier);
                break;
            case REDEMPTION:
                closeAmount = getRedemptionCashFlow(transaction.getPortfolio(), transaction.getIsin(), multipier);
                break;
            default:
                throw new IllegalArgumentException("ЦБ " + transaction.getIsin() +
                        " не может быть закрыта событием типа " + position.getClosingEvent());
        }
        row.put(CLOSE_AMOUNT, closeAmount);
        row.put(CLOSE_ACCRUED_INTEREST, getTransactionCashFlow(transaction, CashFlowType.ACCRUED_INTEREST, multipier));
        row.put(CLOSE_COMMISSION, getTransactionCashFlow(transaction, CashFlowType.COMMISSION, multipier));
        boolean isLongPosition = isLongPosition(position);
        row.put(FORECAST_TAX, getForecastTax(isLongPosition));
        row.put(PROFIT, getClosedPositionProfit(isLongPosition));
        row.put(YIELD, getClosedPositionYield(isLongPosition));
        return row;
    }

    private boolean isLongPosition(ClosedPosition position) {
        return position.getOpenTransaction().getCount() > 0;
    }

    private Table.Record getPaidInterestProfit(Position position,
                                               PaidInterest paidInterest) {
        Table.Record info = new Table.Record();
        info.put(COUPON, convertPaidInterestToExcelFormula(paidInterest.get(CashFlowType.COUPON, position)));
        info.put(AMORTIZATION, convertPaidInterestToExcelFormula(paidInterest.get(CashFlowType.AMORTIZATION, position)));
        info.put(DIVIDEND, convertPaidInterestToExcelFormula(paidInterest.get(CashFlowType.DIVIDEND, position)));
        info.put(TAX, convertPaidInterestToExcelFormula(paidInterest.get(CashFlowType.TAX, position)));
        return info;
    }

    private BigDecimal getTransactionCashFlow(Transaction transaction, CashFlowType type, double multiplier) {
        if (transaction.getId() == null) {
            return null;
        }
        return transactionCashFlowRepository
                .findByPkPortfolioAndPkTransactionIdAndPkType(
                        transaction.getPortfolio(),
                        transaction.getId(),
                        type.getId())
                .map(cash -> cash.getValue()
                        .multiply(exchangeRateToSecurityCurrency(cash.getCurrency(), transaction))
                        .multiply(BigDecimal.valueOf(multiplier))
                        .abs()
                        .setScale(6, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private BigDecimal getRedemptionCashFlow(String portfolio, String isin, double multiplier) {
        List<SecurityEventCashFlowEntity> cashFlows = securityEventCashFlowRepository
                .findByPortfolioIdAndSecurityIsinAndCashFlowTypeIdOrderByTimestampAsc(
                        portfolio,
                        isin,
                        CashFlowType.REDEMPTION.getId());
        if (cashFlows.isEmpty()) {
            return null;
        } else if (cashFlows.size() != 1) {
            throw new IllegalArgumentException("По ЦБ может быть не более одного события погашения, по бумаге " + isin +
                    " найдено " + cashFlows.size() + " событий погашения: " + cashFlows);
        }
        return cashFlows.get(0)
                .getValue()
                .multiply(BigDecimal.valueOf(multiplier))
                .abs()
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal exchangeRateToSecurityCurrency(String currency, Transaction transaction) {
        String securityCurrency = getSecurityCurrency(transaction);
        return currency.equalsIgnoreCase(securityCurrency) ?
                BigDecimal.ONE :
                foreignExchangeRateService.getExchangeRate(currency, securityCurrency);
    }

    /**
     * @return security price currency
     */
    private String getSecurityCurrency(Transaction transaction) {
        String currency = securityCurrencies.get(transaction.getIsin());
        if (currency != null) {
            return currency;
        }
        currency = transactionCashFlowRepository
                .findByPkPortfolioAndPkTransactionIdAndPkType(
                        transaction.getPortfolio(),
                        transaction.getId(),
                        CashFlowType.PRICE.getId())
                .map(TransactionCashFlowEntity::getCurrency)
                .orElseThrow();
        securityCurrencies.put(transaction.getIsin(), currency);
        return currency;
    }

    public static <T extends Position> String convertPaidInterestToExcelFormula(List<SecurityEventCashFlow> pays) {
        if (pays == null || pays.isEmpty()) {
            return null;
        }
        return pays.stream()
                .map(SecurityEventCashFlow::getValue)
                .map(BigDecimal::abs)
                .map(String::valueOf)
                .collect(Collectors.joining("+", "=", ""));
    }

    private String getForecastTax(boolean isLongPosition) {
        String open = "(" + OPEN_AMOUNT.getCellAddr() + "+" + OPEN_ACCRUED_INTEREST.getCellAddr() + ")";
        String close = "(" + CLOSE_AMOUNT.getCellAddr() + "+" + CLOSE_ACCRUED_INTEREST.getCellAddr() + ")";
        String commission = "(" + OPEN_COMMISSION.getCellAddr() + "+" + CLOSE_COMMISSION.getCellAddr() + ")";
        String buy = isLongPosition ? open : close;
        String cell = isLongPosition ? close : open;
        String forecastTaxFormula = cell + "+" + AMORTIZATION.getCellAddr() + "-" + buy + "-" + commission;
        return "=IF(" + forecastTaxFormula + "<0,0,0.13*(" + forecastTaxFormula + "))";
    }

    private String getClosedPositionProfit(boolean isLongPosition) {
        String open = "(" + OPEN_AMOUNT.getCellAddr() + "+" + OPEN_ACCRUED_INTEREST.getCellAddr() + ")";
        String close = "(" + CLOSE_AMOUNT.getCellAddr() + "+" + CLOSE_ACCRUED_INTEREST.getCellAddr() + ")";
        String openCommission = OPEN_COMMISSION.getCellAddr();
        String closeCommission = CLOSE_COMMISSION.getCellAddr();
        String commission = "(" + openCommission + "+" + closeCommission + ")";
        String payments = "(" + COUPON.getCellAddr() + "+" + AMORTIZATION.getCellAddr() + "+" + DIVIDEND.getCellAddr() + ")";
        String tax = "(" + TAX.getCellAddr() + "+" + FORECAST_TAX.getCellAddr() + ")";

        String buy = isLongPosition ? open : close;
        String cell = isLongPosition ? close : open;

        return "=" + cell + "+" + payments + "-" + buy + "-" + tax + "-" + commission;
    }

    private String getClosedPositionYield(boolean isLongPosition) {
        String profit = getClosedPositionProfit(isLongPosition).replace("=", "");
        String open = "(" + OPEN_AMOUNT.getCellAddr() + "+" + OPEN_ACCRUED_INTEREST.getCellAddr() + ")";
        String openCommission = OPEN_COMMISSION.getCellAddr();
        // TODO DAYS() excel function not impl by Apache POI: https://bz.apache.org/bugzilla/show_bug.cgi?id=58468
        String multiplicator = "100*365/(1+ABS(DAYS360(" + OPEN_DATE.getCellAddr() + "," + CLOSE_DATE.getCellAddr() + ")))";
        return "=(" + profit + ")" +
                "/(" + open + "+" + openCommission + ")" +
                "*" + multiplicator;
    }
}
