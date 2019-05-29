
INTERFACE GCD(a) {
    METHOD/Action set_n (a n)
    METHOD/Action set_m (a m)
    METHOD result a
}

MODULE GCD(Bit(32)) {
        INTERFACE GCD(Bit(32))
        FIELD Bit(32) n
        FIELD Bit(32) m
        METHOD/Rule/Action swap if (((n > m) && (m !=  0))) {
               STORE : n = m 
               STORE : m = n 
        }
        METHOD/Rule/Action sub if (((n <= m) && (m !=  0))) {
               STORE : m = (m - n) 
        }
        METHOD/Action set_n ( Bit_32 in_n ) if ((m ==  0)) {
        STORE : n = in_n 
        }
        METHOD/Action set_m ( Bit_32 in_m ) if ((m ==  0)) {
        STORE : m = in_m 
        }
        METHOD result Bit_32 = (n) if ((m ==  0)) {

        }
}
MODULE mkMain {
        FIELD GCD(Bit(32)) gcd
        FIELD Bit(1) started
        FIELD Bit(32) dv
        METHOD/Rule/Action rl_start if ((started ==  0)) {
               CALL/Action : gcd.set_n(  100)
               CALL/Action : gcd.set_m(  20)
               STORE : started =  1 
        }
        METHOD/Rule/Action rl_display {
               LET a v = gcd.result () 
               STORE : dv = v 
        }
}
