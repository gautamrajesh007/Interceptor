#!/bin/zsh

# Exit immediately if a command fails
set -e

echo "Starting PostgreSQL setup..."

PGPASSWORD='testpass@123' psql -h localhost -p 5432 -U testuser -d testdb "sslmode=require" <<'SQL'

DROP TABLE IF EXISTS order_items, orders, products, customers, employees, departments, payments, suppliers, inventory, categories CASCADE;

CREATE TABLE categories (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE customers (
    id SERIAL PRIMARY KEY,
    name TEXT,
    email TEXT,
    city TEXT
);

CREATE TABLE suppliers (
    id SERIAL PRIMARY KEY,
    name TEXT,
    contact_email TEXT
);

CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name TEXT,
    price NUMERIC(10,2),
    category_id INT REFERENCES categories(id),
    supplier_id INT REFERENCES suppliers(id)
);

CREATE TABLE inventory (
    id SERIAL PRIMARY KEY,
    product_id INT REFERENCES products(id),
    stock INT
);

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    customer_id INT REFERENCES customers(id),
    order_date DATE
);

CREATE TABLE order_items (
    id SERIAL PRIMARY KEY,
    order_id INT REFERENCES orders(id),
    product_id INT REFERENCES products(id),
    quantity INT
);

CREATE TABLE payments (
    id SERIAL PRIMARY KEY,
    order_id INT REFERENCES orders(id),
    amount NUMERIC(10,2),
    payment_method TEXT
);

CREATE TABLE departments (
    id SERIAL PRIMARY KEY,
    name TEXT
);

CREATE TABLE employees (
    id SERIAL PRIMARY KEY,
    name TEXT,
    department_id INT REFERENCES departments(id),
    salary INT
);

INSERT INTO categories (name) VALUES
('Electronics'), ('Clothing'), ('Books'), ('Furniture'), ('Toys'),
('Groceries'), ('Sports'), ('Beauty'), ('Automotive'), ('Garden');

INSERT INTO customers (name, email, city) VALUES
('Alice','alice@mail.com','NY'),
('Bob','bob@mail.com','LA'),
('Charlie','charlie@mail.com','Chicago'),
('David','david@mail.com','Houston'),
('Eva','eva@mail.com','Phoenix'),
('Frank','frank@mail.com','Seattle'),
('Grace','grace@mail.com','Miami'),
('Hank','hank@mail.com','Boston'),
('Ivy','ivy@mail.com','Denver'),
('Jack','jack@mail.com','Dallas');

INSERT INTO suppliers (name, contact_email) VALUES
('Supplier1','s1@mail.com'),
('Supplier2','s2@mail.com'),
('Supplier3','s3@mail.com'),
('Supplier4','s4@mail.com'),
('Supplier5','s5@mail.com'),
('Supplier6','s6@mail.com'),
('Supplier7','s7@mail.com'),
('Supplier8','s8@mail.com'),
('Supplier9','s9@mail.com'),
('Supplier10','s10@mail.com');

INSERT INTO products (name, price, category_id, supplier_id) VALUES
('Laptop',1000,1,1),
('Shirt',20,2,2),
('Book A',15,3,3),
('Sofa',500,4,4),
('Toy Car',10,5,5),
('Apple',2,6,6),
('Football',25,7,7),
('Lipstick',12,8,8),
('Car Oil',30,9,9),
('Shovel',18,10,10);

INSERT INTO inventory (product_id, stock) VALUES
(1,50),(2,200),(3,150),(4,20),(5,300),
(6,500),(7,100),(8,250),(9,80),(10,60);

INSERT INTO orders (customer_id, order_date) VALUES
(1,'2024-01-01'),(2,'2024-01-02'),(3,'2024-01-03'),
(4,'2024-01-04'),(5,'2024-01-05'),(6,'2024-01-06'),
(7,'2024-01-07'),(8,'2024-01-08'),(9,'2024-01-09'),(10,'2024-01-10');

INSERT INTO order_items (order_id, product_id, quantity) VALUES
(1,1,1),(2,2,2),(3,3,1),(4,4,1),(5,5,3),
(6,6,5),(7,7,2),(8,8,1),(9,9,2),(10,10,1);

INSERT INTO payments (order_id, amount, payment_method) VALUES
(1,1000,'Card'),
(2,40,'Cash'),
(3,15,'UPI'),
(4,500,'Card'),
(5,30,'Cash'),
(6,10,'UPI'),
(7,50,'Card'),
(8,12,'Cash'),
(9,60,'UPI'),
(10,18,'Card');

INSERT INTO departments (name) VALUES
('HR'),('IT'),('Finance'),('Marketing'),('Sales'),
('Support'),('Admin'),('R&D'),('Logistics'),('Legal');

INSERT INTO employees (name, department_id, salary) VALUES
('Emp1',1,50000),
('Emp2',2,60000),
('Emp3',3,55000),
('Emp4',4,52000),
('Emp5',5,58000),
('Emp6',6,48000),
('Emp7',7,47000),
('Emp8',8,65000),
('Emp9',9,53000),
('Emp10',10,70000);

SQL

echo "Database setup completed successfully!"