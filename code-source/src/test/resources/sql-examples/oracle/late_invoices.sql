-- Unpaid invoices more than 30 days old, with the customer's name where
-- there is one, using Oracle's older outer join syntax.
SELECT
  i.invoice_no,
  NVL(c.name, 'Unknown customer') AS customer,
  DECODE(i.status, 'O', 'Open', 'D', 'Disputed', 'Other') AS status,
  TO_CHAR(i.issued_on, 'YYYY-MM-DD') AS issued,
  TRUNC(SYSDATE) - TRUNC(i.issued_on) AS days_old
FROM finance.invoices i, finance.customers c
WHERE c.customer_id (+) = i.customer_id
  AND i.paid_on IS NULL
  AND i.issued_on < TRUNC(SYSDATE) - 30
  AND i.issued_on >= TO_DATE('{{ from_dttm[:10] }}', 'YYYY-MM-DD')
  AND ROWNUM <= 500
